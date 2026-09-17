import java.util.Random;
import dev.nonamecrackers2.simpleclouds.client.noise.PsrdNoise;

/**
 * Standalone regression checks for the CPU translation of the bundled
 * assets/simpleclouds/shaders/include/psrdnoise.glsl (MIT, Gustavson/McEwan).
 * The reference below uses vectors/matrices and double precision, independently
 * of the production scalar float expressions. Tolerance covers float rounding.
 * Golden values were computed independently from the GLSL vector equations,
 * never harvested from PsrdNoise. No Minecraft client/GPU is required.
 */
public final class PsrdNoiseTest {
    private static int checks;
    private static final double[][] GOLDEN = {
        {0.17, 0.31, 0.43, 0, 0, 0, 0, -0.4820958907762376, -2.9942504868952025, -1.8432310123451778, -1.9987580427642553},
        {0.61, 0.23, 0.12, 0, 0, 0, 0.7, -0.42878174524253493, -1.543706247471005, 1.3876956630319268, 0.03710875203141837},
        {-0.17, -0.31, -0.43, 0, 0, 0, 0.7, 0.2797044616805213, -2.2568372332134796, -1.474202061784439, -3.396562085707996},
        {-3.21, 0.37, 5.13, 256, 256, 256, -2.3, 0.30603781629835286, 1.2541730480313853, -2.3487850519686106, -0.10559079548223951},
        {-12.3, 0.71, -0.23, 256, 256, 256, 0.7, -0.5138526804966409, 2.4172879408857466, 2.0243540828730375, 1.6812650477904771},
        {3.21, -4.13, 1.37, 8, 10, 12, 1.4, -0.1783147651581157, 1.9422570909239236, -1.8993823899248021, 2.330439234504903},
        {0.25, 0.25, 0.25, 0, 0, 0, 0, -0.10432732062021097, -2.772293619847739, -2.682344595922372, -2.3979902491744123},
        {0.1, 0.2, 0.3, 0, 0, 0, 0.5, 0.48137968901579564, -2.860472429739039, -2.8590985421874873, -2.764255728897656}
    };
    private static final double[][] M={{0,1,1},{1,0,1},{1,1,0}};
    private static final double[][] MI={{-.5,.5,.5},{.5,-.5,.5},{.5,.5,-.5}};

    private static void near(double actual,double expected,double tolerance,String label) {
        checks++;
        if(!Double.isFinite(actual) || Math.abs(actual-expected)>tolerance)
            throw new AssertionError(label+": "+actual+" != "+expected+" (tol "+tolerance+")");
    }
    private static double mod(double x,double y) { return x-y*Math.floor(x/y); }
    private static double hash(double i) { double v=mod(i,289);return mod((v*34+10)*v,289); }
    private static double[] multiply(double[][] matrix,double[] vector) {
        double[] result=new double[3];
        for(int row=0;row<3;row++) for(int col=0;col<3;col++) result[row]+=matrix[row][col]*vector[col];
        return result;
    }

    /** Literal vector-level translation of the repository's GLSL primary source. */
    private static double[] reference(double[] point,double[] period,double alpha) {
        double[] u=multiply(M,point),base=new double[3],f=new double[3];
        for(int i=0;i<3;i++){base[i]=Math.floor(u[i]);f[i]=u[i]-base[i];}
        double[] edges={f[0],f[1],f[0]},values={f[1],f[2],f[2]},rank=new double[3];
        for(int i=0;i<3;i++) rank[i]=values[i]>=edges[i]?1:0;
        double[] g={1-rank[2],rank[0],rank[1]},l={1-rank[0],1-rank[1],rank[2]};
        double[][] corners=new double[4][3];
        for(int i=0;i<3;i++){
            corners[0][i]=base[i]; corners[1][i]=base[i]+Math.min(g[i],l[i]);
            corners[2][i]=base[i]+Math.max(g[i],l[i]); corners[3][i]=base[i]+1;
        }
        double[] result=new double[4];
        for(double[] corner:corners) {
            double[] v=multiply(MI,corner),d=new double[3];
            for(int i=0;i<3;i++) d[i]=point[i]-v[i];
            if(period[0]>0 || period[1]>0 || period[2]>0){
                for(int i=0;i<3;i++) if(period[i]>0)v[i]=mod(v[i],period[i]);
                corner=multiply(M,v);
                for(int i=0;i<3;i++)corner[i]=Math.floor(corner[i]+.5);
            }
            double h=hash(hash(hash(corner[2])+corner[1])+corner[0]);
            double theta=h*3.883222077,sz=h*-.006920415+.996539792,psi=h*.108705628;
            double ct=Math.cos(theta),st=Math.sin(theta),radial=Math.sqrt(1-sz*sz);
            double[] gradient={ct*radial,st*radial,sz};
            if(alpha!=0){
                double sp=Math.sin(psi),cp=Math.cos(psi),ctp=st*sp-ct*cp;
                double[] rotated={(1-sz)*ctp*st+sz*sp,(1-sz)*-ctp*ct+sz*cp,
                    -(gradient[1]*cp+gradient[0]*sp)};
                for(int i=0;i<3;i++)gradient[i]=Math.cos(alpha)*gradient[i]+Math.sin(alpha)*rotated[i];
            }
            double radius2=0,dot=0;
            for(int i=0;i<3;i++){radius2+=d[i]*d[i];dot+=gradient[i]*d[i];}
            double w=Math.max(.5-radius2,0);
            result[0]+=w*w*w*dot;
            for(int i=0;i<3;i++)result[i+1]+=w*w*w*gradient[i]-6*w*w*dot*d[i];
        }
        for(int i=0;i<4;i++)result[i]*=39.5;
        return result;
    }
    private static double[] actual(float[] p,float[] period,float alpha) {
        float[] gradient=new float[3];
        float n=PsrdNoise.noise(p[0],p[1],p[2],period[0],period[1],period[2],alpha,gradient);
        return new double[]{n,gradient[0],gradient[1],gradient[2]};
    }
    private static double[] doubles(float[] v){return new double[]{v[0],v[1],v[2]};}
    public static void main(String[] args){
        for(int k=0;k<GOLDEN.length;k++) {
            double[] row=GOLDEN[k];
            float[] p={(float)row[0],(float)row[1],(float)row[2]},period={(float)row[3],(float)row[4],(float)row[5]};
            double[] observed=actual(p,period,(float)row[6]);
            for(int component=0;component<4;component++)
                near(observed[component],row[7+component],component==0?.0004:.002,"golden "+k+" component "+component);
        }
        Random random=new Random(0x53434c4f55444cL);
        float[][] periods={{0,0,0},{256,256,256},{8,10,12},{8,0,12}};
        for(int k=0;k<4096;k++) {
            float[] p={(random.nextFloat()-.5f)*30,(random.nextFloat()-.5f)*30,(random.nextFloat()-.5f)*30};
            float[] period=periods[k%periods.length];float alpha=(random.nextFloat()-.5f)*12;
            double[] expected=reference(doubles(p),doubles(period),alpha),observed=actual(p,period,alpha);
            for(int component=0;component<4;component++)
                near(observed[component],expected[component],component==0?.0004:.002,"vector parity "+k+" component "+component);
        }
        // Negative coordinates must tile too, including a crossing of the origin.
        for(float x:new float[]{-19.125f,-.375f,.125f,3.875f})
            for(int axis=0;axis<3;axis++) {
                float[] p={x,.3125f,-.6875f},period={8,10,12};
                double[] before=actual(p,period,.7f);
                p[axis]+=period[axis];
                double[] after=actual(p,period,.7f);
                for(int component=0;component<4;component++)
                    near(after[component],before[component],.00001,"periodicity "+x+" axis "+axis);
            }
        // Equal fractional simplex ranks and integer cells must not introduce jumps.
        for(float value:new float[]{-.75f,-.25f,0,.25f,.75f,1f})
            for(int axis=0;axis<3;axis++) {
                float[] p={value,value,value},period={256,256,256};
                p[axis]-=.0001f;double[] left=actual(p,period,1.1f);
                p[axis]+=.0002f;double[] right=actual(p,period,1.1f);
                near(right[0],left[0],.003,"simplex continuity "+value+" axis "+axis);
                for(int component=1;component<4;component++)
                    near(right[component],left[component],.03,"gradient continuity "+value+" axis "+axis);
            }
        // Analytic derivatives must agree with independently sampled finite differences.
        for(int k=0;k<64;k++){
            float[] p={(random.nextFloat()-.5f)*4,(random.nextFloat()-.5f)*4,(random.nextFloat()-.5f)*4};
            float[] period={256,256,256};float alpha=.7f;
            double[] center=actual(p,period,alpha);
            for(int axis=0;axis<3;axis++){
                float saved=p[axis];p[axis]=saved-.001f;double low=actual(p,period,alpha)[0];
                p[axis]=saved+.001f;double high=actual(p,period,alpha)[0];p[axis]=saved;
                near((high-low)/.002,center[axis+1],.015,"finite difference "+k+" axis "+axis);
            }
        }
        System.out.println("PASS: "+checks+" independent PsrdNoise checks (golden, vector parity, negative-period tiling, boundary continuity, derivatives)");
    }
}
