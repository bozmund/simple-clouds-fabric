import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import com.google.gson.*;

/** Structural/source identity gate, not a licensing clearance or runtime test. */
public final class VerifyReleaseArtifact {
    static void require(boolean ok,String message) {if(!ok)throw new IllegalStateException(message);}
    static byte[] entry(ZipFile zip,String name) throws Exception {
        var e=zip.getEntry(name);require(e!=null,"Missing packaged entry: "+name);
        try(var in=zip.getInputStream(e)){return in.readAllBytes();}
    }
    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    public static void main(String[] args) throws Exception {
        require(args.length==2,"Usage: VerifyReleaseArtifact.java <integrated-jar> <weather-libraries>");
        Path artifact=Path.of(args[0]),libraries=Path.of(args[1]);
        require(Files.isRegularFile(artifact)&&!Files.isSymbolicLink(artifact),"Unsafe artifact path");
        try(var zip=new ZipFile(artifact.toFile())) {
            var metadata=JsonParser.parseString(new String(entry(zip,"fabric.mod.json"),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            require(metadata.get("id").getAsString().equals("simpleclouds"),"Wrong mod ID");
            require(metadata.get("version").getAsString().equals("0.7.3+26.3-fabric"),"Unexpected artifact version");
            require(metadata.getAsJsonObject("depends").get("minecraft").getAsString().equals("~26.3"),"Wrong Minecraft target");
            for(String name:List.of("LICENSE_simple-clouds","META-INF/licenses/simpleclouds/LICENSE.md",
                "META-INF/licenses/simpleclouds/THIRD-PARTY-NOTICES.md","META-INF/licenses/simpleclouds/WEATHER-SOURCE-AND-RELINKING.md"))
                require(entry(zip,name).length>100,"Truncated notice: "+name);
            for(String name:List.of("particle-rain.LICENSE","immersive-storms.LICENSE","GPL-3.0.LICENSE"))
                require(Arrays.equals(entry(zip,"META-INF/licenses/simpleclouds-weather/"+name),Files.readAllBytes(libraries.resolve(name))),"License mismatch: "+name);
            var nested=new HashSet<String>();
            for(var item:metadata.getAsJsonArray("jars"))nested.add(item.getAsJsonObject().get("file").getAsString());
            for(String name:List.of("particle-rain.jar","immersive-storms.jar")) {
                String path="META-INF/jars/"+name;require(nested.contains(path),"Unregistered nested module: "+name);
                byte[] packaged=entry(zip,path),sourceBuilt=Files.readAllBytes(libraries.resolve(name));
                require(Arrays.equals(packaged,sourceBuilt),"Nested module differs from supplied build: "+name);
                System.out.println("PASS nested source-built module "+name+" sha256="+sha(packaged));
            }
            require(nested.stream().anyMatch(name->name.contains("yet-another-config-lib")),"Missing nested YACL");
            byte[] main=entry(zip,"dev/nonamecrackers2/simpleclouds/SimpleCloudsMod.class");
            require(main.length>8 && (main[6]&255)==0 && (main[7]&255)==69,"Unexpected Java bytecode target (requires Java25)");
            System.out.println("PASS integrated metadata, Java25, complete notices and exact nested build identities");
        }
        System.out.println("Artifact sha256="+sha(Files.readAllBytes(artifact)));
    }
}
