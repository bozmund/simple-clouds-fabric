import java.nio.file.*;
import javax.imageio.ImageIO;
/** Diagnostic only: count strongly saturated fragments in the top sky region.
 * Not a general image-quality or parity metric; requires the GRIDSTORM fixture.
 */
public final class RainGpuArtifactProbe {
    public static void main(String[] args) throws Exception {
        for(String arg:args) {
            long total=0,maximum=0;int count=0,affected=0;
            try(var files=Files.list(Path.of(arg))) {
                for(Path path:files.filter(p->p.getFileName().toString().matches("devshot-SHAKE-[0-9]+\\.png")).sorted().toList()) {
                    var image=ImageIO.read(path.toFile());
                    if(image==null) throw new IllegalStateException("Not a PNG: "+path);
                    long colored=0;
                    for(int y=0;y<(int)(image.getHeight()*.65);y++) for(int x=0;x<image.getWidth();x++) {
                        int rgb=image.getRGB(x,y),r=(rgb>>16)&255,g=(rgb>>8)&255,b=rgb&255;
                        int max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b));
                        if(max>=110 && max-min>=90 && max>=3*min+30) colored++;
                    }
                    count++;total+=colored;maximum=Math.max(maximum,colored);if(colored>0)affected++;
                    image.flush();
                }
            }
            if(count!=81) throw new IllegalStateException("Expected 81 exact fixture frames, got "+count);
            System.out.printf("%s frames=%d affected=%d saturatedPixels=%d maxPerFrame=%d%n",arg,count,affected,total,maximum);
        }
    }
}
