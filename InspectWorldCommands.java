import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import net.minecraft.nbt.*;

/** Read-only region scan for command blocks. No RegionFile writer is opened. */
public class InspectWorldCommands {
    public static void main(String[] args) throws Exception {
        int chunks = 0, commands = 0;
        try (var paths = Files.list(Path.of(args[0]))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".mca")).toList()) {
                try (var file = new RandomAccessFile(path.toFile(), "r")) {
                    if(file.length()<8192) { System.out.println("SKIP incomplete region "+path.getFileName()); continue; }
                    int[] offsets = new int[1024];
                    for (int i=0;i<1024;i++) offsets[i]=file.readInt();
                    for(int entry:offsets) {
                        if(entry==0) continue;
                        long offset=(long)(entry>>>8)*4096;
                        file.seek(offset);
                        int size=file.readInt();
                        int compression=file.readUnsignedByte();
                        if((compression&128)!=0) { System.out.println("SKIP external chunk "+path); continue; }
                        if(size<=1 || size>16*1024*1024 || offset+4+size>file.length()) continue;
                        byte[] bytes=new byte[size-1]; file.readFully(bytes);
                        InputStream raw=new ByteArrayInputStream(bytes);
                        InputStream input=switch(compression) {
                            case 1 -> new GZIPInputStream(raw);
                            case 2 -> new InflaterInputStream(raw);
                            case 3 -> raw;
                            case 4 -> (InputStream)Class.forName("net.jpountz.lz4.LZ4BlockInputStream").getConstructor(InputStream.class).newInstance(raw);
                            default -> throw new IOException("Unknown compression "+compression);
                        };
                        try(var data=new DataInputStream(input)) {
                            var chunk=NbtIo.read(data,NbtAccounter.create(32L*1024*1024));
                            chunks++;
                            for(var tag:chunk.getListOrEmpty("block_entities")) {
                                if(tag instanceof CompoundTag block && block.getStringOr("id", "").equals("minecraft:command_block")) {
                                    commands++;
                                    System.out.println(path.getFileName()+" x="+block.get("x")+" y="+block.get("y")+" z="+block.get("z")+" auto="+block.get("auto")+" command="+block.get("Command"));
                                }
                            }
                        }
                    }
                }
            }
        }
        System.out.println("READ-ONLY scanned chunks="+chunks+" command_blocks="+commands);
    }
}
