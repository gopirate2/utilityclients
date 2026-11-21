package com.baggute.cheatclient.injector;
import java.io.IOException;
import java.nio.file.*;
import java.util.zip.ZipFile;
import com.google.gson.JsonParser;

/** Installs the complete client before Fabric starts, including nested Baritone and its mixins. */
public final class StartupModInstaller {
    private StartupModInstaller(){}
    public static Path install(Path client,Path mods)throws IOException{
        client=client.toAbsolutePath().normalize();mods=mods.toAbsolutePath().normalize();
        if(!Files.isRegularFile(client)||!Files.isDirectory(mods)||Files.isSymbolicLink(mods))throw new IOException("Select an existing client JAR and the Fabric profile's mods folder");
        if(!isClient(client))throw new IOException("The selected JAR is not a UtilityClient Fabric mod");
        Path target=mods.resolve("utilityclient.jar");int matches=0;
        try(var files=Files.list(mods)){
            for(Path existing:files.filter(p->p.getFileName().toString().endsWith(".jar")).toList())if(isClient(existing)){
                target=existing;matches++;
            }
        }
        if(matches>1)throw new IOException("This mods folder contains multiple UtilityClient copies; remove duplicates first");
        if(Files.exists(target)&&!isClient(target))throw new IOException("The destination filename belongs to another mod");
        installBaritone(client,mods);
        if(Files.exists(target)&&Files.mismatch(client,target)==-1)return target;
        Path staged=Files.createTempFile(mods,".utilityclient-",".tmp");
        try{
            Files.copy(client,staged,StandardCopyOption.REPLACE_EXISTING);
            if(Files.mismatch(client,staged)!=-1)throw new IOException("Client copy verification failed");
            if(Files.exists(target)){
                Path backups=InstallPaths.root().resolve("backups/startup-mods");Files.createDirectories(backups);
                String digest=hash(target);
                Path backup=backups.resolve(digest+".jar");if(!Files.exists(backup))Files.copy(target,backup);
            }
            try{Files.move(staged,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException ignored){Files.move(staged,target,StandardCopyOption.REPLACE_EXISTING);}
            return target;
        }finally{Files.deleteIfExists(staged);}
    }
    private static boolean isClient(Path path){
        if(Files.isSymbolicLink(path))return false;
        try(var zip=new ZipFile(path.toFile())){
            var metadata=zip.getEntry("fabric.mod.json");if(metadata==null)return false;
            try(var input=zip.getInputStream(metadata)){
                String id=JsonParser.parseString(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().get("id").getAsString();
                return id.equals("cheatclient")||id.equals("utilityclient");
            }
        }catch(Exception ignored){return false;}
    }
    private static void installBaritone(Path client,Path mods)throws IOException{
        // Preserve an existing standalone Baritone installation.
        try(var files=Files.list(mods)){
            for(Path existing:files.filter(p->p.toString().endsWith(".jar")).toList()){
                if(Files.isSymbolicLink(existing))continue;
                try(var zip=new ZipFile(existing.toFile())){
                    var metadata=zip.getEntry("fabric.mod.json");if(metadata==null)continue;
                    try(var input=zip.getInputStream(metadata)){
                        var json=JsonParser.parseString(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                        if(json.has("id")&&json.get("id").getAsString().equals("baritone"))return;
                    }
                }catch(Exception ignored){}
            }
        }
        try(var zip=new ZipFile(client.toFile())){
            var entry=zip.getEntry("sideload/baritone.jar");if(entry==null)return;
            Path target=mods.resolve("baritone-utilityclient.jar");
            if(Files.exists(target))throw new IOException("Baritone destination belongs to another file");
            Path staged=Files.createTempFile(mods,".baritone-",".tmp");
            try{
                try(var input=zip.getInputStream(entry)){Files.copy(input,staged,StandardCopyOption.REPLACE_EXISTING);}
                try(var baritone=new ZipFile(staged.toFile())){
                    var metadata=baritone.getEntry("fabric.mod.json");if(metadata==null)throw new IOException("Invalid Baritone side-load package");
                    try(var input=baritone.getInputStream(metadata)){
                        if(!JsonParser.parseString(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().get("id").getAsString().equals("baritone"))throw new IOException("Wrong side-load mod");
                    }
                }
                try{Files.move(staged,target,StandardCopyOption.ATOMIC_MOVE);}
                catch(AtomicMoveNotSupportedException ignored){Files.move(staged,target);}
            }finally{Files.deleteIfExists(staged);}
        }
    }
    private static String hash(Path path)throws IOException{
        try{
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            try(var input=Files.newInputStream(path)){byte[] buffer=new byte[65536];int size;while((size=input.read(buffer))>0)digest.update(buffer,0,size);}
            return java.util.HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException failure){throw new IOException(failure);}
    }
}
