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
    private static String hash(Path path)throws IOException{
        try{
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            try(var input=Files.newInputStream(path)){byte[] buffer=new byte[65536];int size;while((size=input.read(buffer))>0)digest.update(buffer,0,size);}
            return java.util.HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException failure){throw new IOException(failure);}
    }
}
