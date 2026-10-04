import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.FrustumIntersection;
import java.nio.file.Files;
import java.nio.file.Path;

/** Modern public boolean culling replaces the obsolete Forge invoker. */
public class FrustumCullParityTest {
	public static void main(String[] args) throws Exception {
		var raw=Frustum.class.getDeclaredMethod("cubeInFrustum",double.class,double.class,double.class,double.class,double.class,double.class);
		raw.setAccessible(true);
		int checks=0;
		for(double cam:new double[]{0,-37.5,128}) {
			var frustum=new Frustum(new Matrix4f(),new Matrix4f());
			frustum.prepare(cam,cam,cam);
			for(double x:new double[]{-3,-1,-0.5,0,0.5,1,3})
			for(double y:new double[]{-3,-1,0,1,3})
			for(double z:new double[]{-3,-1,0,1,3}) {
				AABB box=new AABB(cam+x,cam+y,cam+z,cam+x+0.75,cam+y+0.75,cam+z+0.75);
				int intersection=(int)raw.invoke(frustum,box.minX,box.minY,box.minZ,box.maxX,box.maxY,box.maxZ);
				boolean expected=intersection==FrustumIntersection.INSIDE || intersection==FrustumIntersection.INTERSECT;
				if(frustum.isVisible(box)!=expected) throw new AssertionError("Public culling mismatch");
				checks++;
			}
		}
		String source=Files.readString(Path.of("src/main/java/dev/nonamecrackers2/simpleclouds/client/mesh/generator/CloudMeshGenerator.java"));
		if(source.contains("MixinFrustumAccessor") || !source.contains("frustum.isVisible(new AABB("))
			throw new AssertionError("Legacy unregistered frustum accessor returned");
		System.out.println("PASS modern Frustum public boolean parity: "+checks+" inside/intersect/outside/translated bounds; no accessor dependency");
	}
}
