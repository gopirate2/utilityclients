package com.baggute.cheatclient.injector;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Pulls the client jar from the public release instead of a path on disk.
 *
 * <p>Accepts both shapes a release can take: the bare {@code .jar}, and a
 * {@code .jar.zip} wrapper (what to upload when the uploader refuses the jar
 * itself). A zip is opened and the client jar inside is taken out — nothing else
 * in the archive is trusted or executed.
 *
 * <p>Every request is an unauthenticated GET: there is no token, no credentials
 * file, and nothing to prompt for.
 */
public final class RemoteClient {

    private static final String API = "https://api.github.com";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private RemoteClient() {
    }

    /**
     * A fetch outcome.
     *
     * <p>{@code source} is the provenance line: which repo, which release tag and
     * which asset the client actually came from. It is empty for failures and for
     * a caller-supplied path, because in those cases there is no release to name.
     */
    public record Result(Path jar, String message, boolean ok, String source) {

        public Result(Path jar, String message, boolean ok) {
            this(jar, message, ok, "");
        }
    }

    /** One release asset: the API id used to download it, and its file name. */
    private record Asset(String id, String name, String digest, String checksumId) {
    }

    /** The newest published release, plus the asset chosen inside it. */
    private record Pick(Asset asset, String tag, String published, String title) {
    }

    /** A release paired with the file chosen from it, for ranking. */
    private record Candidate(JsonObject release, Asset asset) {
    }

    /** Public repo the client is published to. No credentials, no per-user setup. */
    private static final String DEFAULT_REPO = "gopirate2/utilityclients";

    /**
     * Fallback tag only, used when the release list cannot be read (rate limit,
     * outage). Pinning this tag was the original bug: every release cut after
     * "ver 1.2" was ignored because the lookup never asked for anything newer.
     */
    private static final String RELEASE_TAG = "client";

    public static String defaultRepo() {
        String override = System.getProperty("utilityclient.repo");
        if (override != null && override.contains("/")) return override.trim();
        return DEFAULT_REPO;
    }

    /**
     * Token, repo name and the fetched jar all live in the hidden install folder,
     * next to the client's own config, so a fetch leaves nothing in the profile
     * root and nothing in the game instance.
     */
    public static Path cacheDir() {
        return InstallPaths.prepare();
    }

    // ---- fetch ----

    // No token, no credentials file, nothing to prompt for: the release lives in a
    // public repo, so every request here is an ordinary unauthenticated GET.
    // An empty string keeps the parameter list readable at call sites that used to
    // pass one; nothing reads it.

    public static Result fetch(String repo, String path) {
        return fetch(repo, path, ignored -> { });
    }
    public record UpdateCheck(boolean available, boolean current, String message, String releaseUrl) {}
    public static UpdateCheck checkLatest(Path current) {
        try {
            Pick pick=latestRelease(defaultRepo());
            return describeUpdate(pick, current);
        } catch(Exception failure) {
            return new UpdateCheck(false,false,"Update check unavailable: "+describe(failure)+". The selected local client is still available.","");
        }
    }
    private static UpdateCheck describeUpdate(Pick pick, Path current) throws IOException {
        if(pick==null) return new UpdateCheck(false,false,"No compatible stable GitHub release is published.","");
        var identity=java.util.regex.Pattern.compile("^UtilityClient ([0-9]+\\.[0-9]+\\.[0-9]+) \\u2014 ([0-9a-f]{16})$").matcher(pick.title());
        boolean matches=false;
        if(identity.matches() && current!=null && Files.isRegularFile(current))
            matches=ClientJar.versionOf(current).equals(identity.group(1)) && ClientIntegrity.sha256(current).startsWith(identity.group(2));
        String message=matches?"You're up to date: "+pick.title():"Update available: "+pick.title()+". Choose FETCH CLIENT to download and verify it.";
        return new UpdateCheck(true,matches,message,"https://github.com/"+defaultRepo()+"/releases/tag/"+pick.tag());
    }

    public static Result fetch(String repo, String path, java.util.function.Consumer<String> progress) {
        if (repo == null || !repo.contains("/")) {
            return new Result(null, "repo must look like owner/name", false);
        }

        Path target = cacheDir().resolve("cheatclient.jar");
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException failure) {
            return new Result(null, "cannot create " + target.getParent(), false);
        }

        try {
            if (path != null && !path.isBlank()) {
                progress.accept("Downloading and validating client");
                String digest = publishedChecksum(contentsUrl(repo, path + ".sha256"), Accept.RAW, Path.of(path).getFileName().toString());
                Result fromPath = download(contentsUrl(repo, path), Accept.RAW, target, "contents " + path, digest);
                if (!fromPath.ok()) return fromPath;
                return new Result(fromPath.jar(), fromPath.message(), true,
                        repo + " @ contents -> " + path);
            }

            progress.accept("Looking up published release");
            Pick pick = latestRelease(repo);
            if (pick == null) {
                return new Result(null, "no usable release asset in " + repo + " and no path given", false);
            }

            progress.accept("Downloading and validating " + pick.asset().name());
            String expected = pick.asset().digest();
            if (expected == null) {
                if (pick.asset().checksumId() == null) return new Result(null, "Release has no published SHA-256 for " + pick.asset().name() + "; use a verified local package or ask for its checksum asset", false);
                expected = publishedChecksum(API + "/repos/" + repo + "/releases/assets/" + pick.asset().checksumId(), Accept.BINARY, pick.asset().name());
            }
            Result fetched = download(API + "/repos/" + repo + "/releases/assets/" + pick.asset().id(),
                    Accept.BINARY, target, "release asset " + pick.asset().name(), expected);
            if (!fetched.ok()) return fetched;

            return new Result(fetched.jar(), fetched.message(), true,
                    repo + " @ " + pick.tag() + " (" + pick.published() + ") -> "
                            + pick.asset().name() + " (asset " + pick.asset().id() + ")");
        } catch (Exception failure) {
            return new Result(null, "fetch failed: " + describe(failure), false);
        }
    }

    private enum Accept {
        /** Raw file body from the contents API; octet-stream returns JSON there. */
        RAW("application/vnd.github.raw"),
        /** Release asset body. */
        BINARY("application/octet-stream");

        private final String header;

        Accept(String header) {
            this.header = header;
        }
    }

    private static String contentsUrl(String repo, String path) {
        return API + "/repos/" + repo + "/contents/" + path.replace(" ", "%20");
    }

    /**
     * Newest published release, then the newest usable asset inside it.
     *
     * <p>Releases are ranked here by {@code published_at}, not by the order the API
     * returns them, and assets by {@code created_at}. Re-uploading a file to an
     * existing release — the usual way a fix ships — leaves the previous file in
     * the asset list, so "first name that matches" returns the stale build for as
     * long as that release exists.
     */
    private static Pick latestRelease(String repo) throws IOException, InterruptedException {
        JsonArray releases = releases(repo);
        if (releases != null) {
            Pick pick = pickNewest(releases);
            if (pick != null) return pick;
        }
        return pickFromTag(repo);
    }

    /** All releases, or null when the list endpoint refuses (rate limit, outage). */
    private static JsonArray releases(String repo) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        API + "/repos/" + repo + "/releases?per_page=100"))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Cache-Control", "no-cache")
                .GET()
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;

        JsonElement parsed = JsonParser.parseString(response.body());
        return parsed.isJsonArray() ? parsed.getAsJsonArray() : null;
    }

    /** Newest non-draft, non-prerelease release that actually carries a usable file. */
    private static Pick pickNewest(JsonArray releases) {
        List<Candidate> candidates = new ArrayList<>();
        for (JsonElement element : releases) {
            if (!element.isJsonObject()) continue;
            JsonObject release = element.getAsJsonObject();
            if (release.has("draft") && release.get("draft").getAsBoolean()) continue;
            if (release.has("prerelease") && release.get("prerelease").getAsBoolean()) continue;
            if (!release.has("assets") || !release.get("assets").isJsonArray()) continue;

            Asset asset = newestAsset(release.getAsJsonArray("assets"));
            if (asset == null) continue;
            candidates.add(new Candidate(release, asset));
        }
        if (candidates.isEmpty()) return null;

        candidates.sort((a, b) -> stamp(b.release()).compareTo(stamp(a.release())));
        Candidate newest = candidates.get(0);
        JsonObject release = newest.release();

        String tag = release.has("tag_name") ? release.get("tag_name").getAsString() : "?";
        return new Pick(newest.asset(), tag, day(stamp(release)), release.has("name") ? release.get("name").getAsString() : tag);
    }

    /** Fallback lookup by the old fixed tag, only when the list is unreadable. */
    private static Pick pickFromTag(String repo) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        API + "/repos/" + repo + "/releases/tags/" + RELEASE_TAG))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Cache-Control", "no-cache")
                .GET()
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("releases/tags/" + RELEASE_TAG + " -> HTTP " + response.statusCode()
                    + describeFailure(repo));
        }

        JsonElement parsed = JsonParser.parseString(response.body());
        if (!parsed.isJsonObject()) return null;
        JsonObject release = parsed.getAsJsonObject();
        if (!release.has("assets") || !release.get("assets").isJsonArray()) return null;

        Asset asset = newestAsset(release.getAsJsonArray("assets"));
        if (asset == null) return null;
        return new Pick(asset, RELEASE_TAG, day(stamp(release)), release.has("name") ? release.get("name").getAsString() : RELEASE_TAG);
    }

    /**
     * Newest usable file in an asset list: bare jar, any jar, then a zip wrapper.
     * Recency decides, because a re-uploaded asset is the fix even when its name
     * never changes; the rank only breaks a same-second tie.
     */
    private static Asset newestAsset(JsonArray assets) {
        Asset best = null;
        String bestStamp = "";
        int bestRank = Integer.MAX_VALUE;

        for (JsonElement element : assets) {
            if (!element.isJsonObject()) continue;
            JsonObject asset = element.getAsJsonObject();
            if (!asset.has("id") || !asset.has("name")) continue;

            String fileName = asset.get("name").getAsString();
            String name = fileName.toLowerCase(java.util.Locale.ROOT);
            if (name.contains("-sources") || name.contains("-dev") || name.contains("injector") || name.contains("setup")) continue;
            boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
            if (windows && name.contains("linux") || !windows && name.contains("windows")) continue;

            int rank;
            if (name.equals("utilityclient-update.zip")) rank = 0;
            else if ((name.startsWith("cheatclient") || name.startsWith("utilityclient")) && name.endsWith(".jar")) rank = 0;
            else if ((name.startsWith("utilityclient") || name.startsWith("cheatclient"))
                    && name.endsWith(".zip")) rank = 2;
            else continue;

            String created = asset.has("created_at") ? asset.get("created_at").getAsString()
                    : asset.has("updated_at") ? asset.get("updated_at").getAsString() : "";

            if (best == null || created.compareTo(bestStamp) > 0
                    || (created.equals(bestStamp) && rank < bestRank)) {
                String digest = asset.has("digest") && !asset.get("digest").isJsonNull() ? asset.get("digest").getAsString() : "";
                digest = digest.startsWith("sha256:") && digest.substring(7).matches("(?i)[0-9a-f]{64}") ? digest.substring(7).toLowerCase(java.util.Locale.ROOT) : null;
                String checksum = null;
                for (JsonElement other : assets) if (other.isJsonObject()) {
                    JsonObject candidate = other.getAsJsonObject();
                    if (candidate.has("name") && candidate.has("id") && candidate.get("name").getAsString().equals(fileName + ".sha256")) checksum = candidate.get("id").getAsString();
                }
                if (checksum == null) for (JsonElement other : assets) if (other.isJsonObject()) {
                    JsonObject candidate = other.getAsJsonObject();
                    if (candidate.has("name") && candidate.has("id") && candidate.get("name").getAsString().equals("SHA256SUMS")) checksum = candidate.get("id").getAsString();
                }
                best = new Asset(asset.get("id").getAsString(), fileName, digest, checksum);
                bestStamp = created;
                bestRank = rank;
            }
        }
        return best;
    }

    /** Publish date with a created-at fallback, so a release still ranks. */
    private static String stamp(JsonObject release) {
        for (String key : new String[]{"published_at", "created_at", "updated_at"}) {
            if (release.has(key) && !release.get(key).isJsonNull()) {
                return release.get(key).getAsString();
            }
        }
        return "";
    }

    private static String day(String iso) {
        return iso.length() >= 10 ? iso.substring(0, 10) : (iso.isBlank() ? "unpublished" : iso);
    }

    /**
     * A 404 from a public repo means the repo name is wrong or the release is gone.
     * One probe says which, so the message is actionable instead of a guess.
     */
    private static String describeFailure(String repo) {
        int repository = probe(API + "/repos/" + repo);
        if (repository == 404) {
            return " — " + repo + " does not exist (or was made private)";
        }
        if (repository != 200) {
            return " — " + repo + " answered HTTP " + repository;
        }
        return " — " + repo + " is reachable, but release tag '" + RELEASE_TAG
                + "' has no assets. Publish one, or name a file inside the repo instead";
    }

    /** HTTP status for a plain GET, or -1 when the call never landed. */
    private static int probe(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .GET()
                    .build();
            return HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception failure) {
            return -1;
        }
    }

    static String parseChecksum(String text, String filename) throws IOException {
        String bare = text.trim();
        if (bare.matches("(?i)[0-9a-f]{64}")) return bare.toLowerCase(java.util.Locale.ROOT);
        String result = null;
        for (String line : text.replace("\r", "").split("\n")) {
            if (line.length() < 66) continue;
            String name = line.substring(64).trim(); if (name.startsWith("*")) name = name.substring(1);
            if (!name.equals(filename)) continue;
            String digest = line.substring(0, 64);
            if (!digest.matches("(?i)[0-9a-f]{64}") || result != null) throw new IOException("Invalid or duplicate published checksum for " + filename);
            result = digest.toLowerCase(java.util.Locale.ROOT);
        }
        if (result == null) throw new IOException("Published SHA-256 is missing for " + filename);
        return result;
    }

    private static String publishedChecksum(String url, Accept accept, String filename) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("Accept", accept.header).header("X-GitHub-Api-Version", "2022-11-28").header("Cache-Control", "no-cache").GET().build();
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200 || response.body().length > 65536) throw new IOException("Published SHA-256 unavailable: HTTP " + response.statusCode());
        return parseChecksum(new String(response.body(), StandardCharsets.UTF_8), filename);
    }

    private static Result download(String url, Accept accept, Path target, String label, String expectedDigest)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .header("Accept", accept.header)
                .header("X-GitHub-Api-Version", "2022-11-28")
                // A stale CDN copy and a stale release look identical from here.
                .header("Cache-Control", "no-cache")
                .GET()
                .build();

        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        Path temp = null;
        java.util.concurrent.ScheduledExecutorService watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "uc-download-timeout");
            thread.setDaemon(true);
            return thread;
        });
        try (InputStream body = response.body()) {
            java.util.concurrent.ScheduledFuture<?> closeBody = watchdog.schedule(() -> {
                try { body.close(); } catch (IOException ignored) {}
            }, Math.max(0L, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS);
            if (response.statusCode() != 200) {
                throw new IOException(label + " -> HTTP " + response.statusCode() + " "
                        + firstLine(new String(body.readNBytes(512), StandardCharsets.UTF_8)));
            }
            temp = Files.createTempFile(target.toAbsolutePath().getParent(), "uc-fetch-", ".bin");
            try (var output = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0L;
                int count;
                while ((count = body.read(buffer)) != -1) {
                    if (System.nanoTime() >= deadline) throw new IOException("Download timed out");
                    total += count;
                    if (total > 256L * 1024 * 1024) throw new IOException("Download exceeds 256 MiB");
                    output.write(buffer, 0, count);
                }
            }
            if (System.nanoTime() >= deadline) throw new IOException("Download timed out");
            closeBody.cancel(false);
            if (!ClientIntegrity.sha256(temp).equals(expectedDigest)) throw new IOException("Client integrity: published download SHA-256 mismatch");
            if (!isZip(temp)) return new Result(null, label + " did not return a jar or zip", false);
            String how = materialise(temp, target, label);
            return new Result(target, "fetched " + label + " (" + Files.size(target) / 1024
                    + " KiB" + (how.isEmpty() ? "" : ", " + how) + ") — published SHA-256 and client structure verified", true);
        } finally {
            watchdog.shutdownNow();
            if (temp != null) Files.deleteIfExists(temp);
        }
    }

    /**
     * Takes either a jar or a zip-wrapped jar and leaves a jar at {@code target}.
     *
     * <p>Exposed because a release cannot always carry a .jar: upload
     * {@code cheatclient.jar.zip} instead and this pulls the inner jar out.
     *
     * @return "" when the input was already a jar, else what was unpacked
     */
    public static String unpackLocal(Path archive, Path target) throws IOException {
        return materialise(archive, target, archive.getFileName().toString());
    }

    private static String materialise(Path downloaded, Path target, String label) throws IOException {
        Path absolute = target.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path pending = Files.createTempFile(absolute.getParent(), "uc-install-", ".jar");
        String innerName = null;
        try {
            // Close every archive/input handle before replacing the destination.
            try (ZipFile zip = new ZipFile(downloaded.toFile())) {
                if (isJar(zip)) {
                    Files.copy(downloaded, pending, StandardCopyOption.REPLACE_EXISTING);
                } else if (zip.getEntry("update.json") != null) {
                    innerName=extractUpdateClient(zip,pending);
                } else {
                    ZipEntry chosen = pickInnerJar(zip);
                    if (chosen == null) throw new IOException(label + " is a zip with no jar inside");
                    innerName = chosen.getName();
                    try (InputStream in = zip.getInputStream(chosen)) {
                        Files.copy(in, pending, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            validateClient(pending);
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(pending,
                    java.nio.file.StandardOpenOption.WRITE)) { channel.force(true); }
            AtomicFiles.commit(pending, absolute);
            return innerName == null ? "" : "unpacked " + innerName;
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private static void validateClient(Path path) throws IOException {
        ClientIntegrity.verify(path);
        try (ZipFile file = new ZipFile(path.toFile())) {
            ZipEntry metadata = file.getEntry("fabric.mod.json");
            if (metadata == null) throw new IOException("Selected artifact is not a Fabric client mod");
            try (InputStream in = file.getInputStream(metadata)) {
                JsonObject mod = JsonParser.parseString(new String(in.readNBytes(1024 * 1024), StandardCharsets.UTF_8)).getAsJsonObject();
                String id = mod.has("id") ? mod.get("id").getAsString() : "";
                if (!id.equals("utilityclient") && !id.equals("cheatclient"))
                    throw new IOException("Wrong client mod identity: " + id);
            } catch (RuntimeException malformed) { throw new IOException("Invalid client metadata", malformed); }
        }
    }
    private static String extractUpdateClient(ZipFile update, Path pending) throws IOException {
        JsonObject metadata;
        try(var input=update.getInputStream(update.getEntry("update.json"))) {
            metadata=JsonParser.parseString(new String(input.readNBytes(65537),StandardCharsets.UTF_8)).getAsJsonObject();
        } catch(RuntimeException malformed) { throw new IOException("Invalid update manifest",malformed); }
        if(!metadata.has("schema") || !metadata.get("schema").getAsString().equals("1")
                || !metadata.has("repository") || !metadata.get("repository").getAsString().equals(defaultRepo())) throw new IOException("Wrong update package format or repository");
        String name=System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT).contains("win")?"Payload.zip":"LinuxBundle.zip";
        ZipEntry entry=update.getEntry(name);
        if(entry==null || entry.getSize()>512L*1024*1024) throw new IOException("Missing or oversized platform bundle");
        String expected=metadata.getAsJsonObject("files").get(name).getAsString();
        if(!expected.matches("(?i)[0-9a-f]{64}")) throw new IOException("Invalid platform bundle checksum");
        Path bundle=Files.createTempFile(pending.getParent(),"uc-platform-",".zip");
        try {
            try(var input=update.getInputStream(entry);var output=Files.newOutputStream(bundle)) {
                byte[] buffer=new byte[65536];long total=0;int count;
                while((count=input.read(buffer))!=-1) { total+=count;if(total>512L*1024*1024)throw new IOException("Platform expansion limit exceeded");output.write(buffer,0,count); }
            }
            if(!ClientIntegrity.sha256(bundle).equalsIgnoreCase(expected))throw new IOException("Platform bundle checksum mismatch");
            try(var zip=new ZipFile(bundle.toFile())) {
                ZipEntry client=zip.getEntry("cheatclient.jar");
                if(client==null||client.getSize()>64L*1024*1024) throw new IOException("Missing or oversized client JAR");
                try(var input=zip.getInputStream(client);var output=Files.newOutputStream(pending)) {
                    byte[] buffer=new byte[65536];long total=0;int count;
                    while((count=input.read(buffer))!=-1) { total+=count;if(total>64L*1024*1024)throw new IOException("Client expansion limit exceeded");output.write(buffer,0,count); }
                }
            }
            return "unpacked "+name+"/cheatclient.jar";
        } finally { Files.deleteIfExists(bundle); }
    }

    /** A jar has classes; a zip-of-jars only has jars. */
    private static boolean isJar(ZipFile zip) {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.equals("fabric.mod.json")) return true;
            if (name.endsWith(".class")) return true;
        }
        return false;
    }

    /** Prefers the client jar, then the largest jar: wrapper zips carry junk. */
    private static ZipEntry pickInnerJar(ZipFile zip) {
        List<ZipEntry> candidates = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;
            String name = entry.getName().toLowerCase(java.util.Locale.ROOT);
            if (!name.endsWith(".jar")) continue;
            if (name.contains("-sources")) continue;
            candidates.add(entry);
        }
        if (candidates.isEmpty()) return null;

        for (ZipEntry entry : candidates) {
            if (entry.getName().toLowerCase(java.util.Locale.ROOT).contains("cheatclient")) return entry;
        }
        candidates.sort((a, b) -> Long.compare(b.getSize(), a.getSize()));
        return candidates.get(0);
    }

    private static boolean isZip(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] magic = in.readNBytes(4);
            return magic.length == 4 && magic[0] == 'P' && magic[1] == 'K';
        } catch (IOException failure) {
            return false;
        }
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        String line = newline < 0 ? text : text.substring(0, newline);
        return line.length() > 140 ? line.substring(0, 140) : line;
    }

    private static String describe(Throwable failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 3 && args[0].equals("unpack")) {
            Path out = Path.of(args[2]);
            String how = unpackLocal(Path.of(args[1]), out);
            System.out.println("ok: " + (how.isEmpty() ? "already a jar" : how) + " -> " + out);
            return;
        }

        String repo = args.length > 0 ? args[0] : defaultRepo();
        String path = args.length > 1 ? args[1] : "";
        Result result = fetch(repo, path);
        System.out.println((result.ok() ? "ok: " : "fail: ") + result.message()
                + (result.jar() == null ? "" : " -> " + result.jar()));
        if (!result.source().isBlank()) System.out.println("from: " + result.source());
        if (!result.ok()) System.exit(1);
    }
}
