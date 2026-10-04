import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Narrow regression for the atmospheric port's misplaced permutation parentheses.
 * Actual shader compilation and rendered behavior require the client tests too. */
public final class AtmosphericNoiseContractTest {
    private static String hashExpression(String text) {
        var match=Pattern.compile("vec4\\s+hash\\s*=\\s*(.*?);",Pattern.DOTALL).matcher(text);
        if(!match.find()) throw new AssertionError("Noise hash expression missing");
        return match.group(1).replaceAll("\\s+","");
    }
    private static int permute(int value) {
        int n=Math.floorMod(value,289);
        return ((n*34+10)*n)%289;
    }
    public static void main(String[] args) throws Exception {
        Path shaders=Path.of("src/main/resources/assets/simpleclouds/shaders");
        String canonical=hashExpression(Files.readString(shaders.resolve("include/psrdnoise.glsl")));
        String atmospheric=hashExpression(Files.readString(shaders.resolve("core/atmospheric_clouds.fsh")));
        if(!canonical.equals(atmospheric)) throw new AssertionError("Atmospheric hash differs from original psrdnoise nesting");
        int cases=0, brokenFailures=0;
        for(int x=0;x<64;x++) for(int y=0;y<64;y++) for(int z=0;z<64;z++) {
            int hash=permute(permute(permute(z)+y)+x);
            double sz=hash*-0.006920415+0.996539792;
            if(hash<0 || hash>=289 || 1-sz*sz<0) throw new AssertionError("Invalid canonical noise gradient");
            // The previous port permuted z twice before adding y, and left x outside.
            int broken=permute(permute(permute(z))+y)+x;
            double brokenSz=broken*-0.006920415+0.996539792;
            if(1-brokenSz*brokenSz<0) brokenFailures++;
            cases++;
        }
        if(brokenFailures==0) throw new AssertionError("Regression fixture does not exercise old NaN gradients");
        System.out.println("PASS atmospheric canonical hash nesting: "+cases+" finite-gradient cases; old expression fails "+brokenFailures);
    }
}
