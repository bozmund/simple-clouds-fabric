import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;

/** Read-only bytecode-name locator, not proof that a located caller ran. */
public class FindDarkEffectCallers {
    public static void main(String[] args) throws Exception {
        try (var files = Files.list(Path.of(args[0]))) {
            for (Path path : files.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) {
                try (var zip = new ZipFile(path.toFile())) {
                    var entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        var entry = entries.nextElement();
                        if (!entry.getName().endsWith(".class")) continue;
                        String data;
                        try (var input = zip.getInputStream(entry)) {
                            data = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
                        }
                        if ((data.contains("BLINDNESS") && data.contains("DARKNESS")) || (data.contains("blindness") && data.contains("darkness")))
                            System.out.println(path.getFileName()+" : "+entry.getName()+" blindness="+data.contains("BLINDNESS")+" darkness="+data.contains("DARKNESS"));
                    }
                }
            }
        }
    }
}
