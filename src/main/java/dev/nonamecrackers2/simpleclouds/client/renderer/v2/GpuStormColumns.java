package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import java.nio.ByteBuffer;
import java.util.List;

/** Derives the existing storm-fog columns from a completed GPU opaque mesh.
 * Every occupied column has an uppermost opaque cell and therefore a +Y face
 * inside the finite generation band. Only that face is needed for this metric.
 */
public final class GpuStormColumns {
    private GpuStormColumns() {}

    public record Result(byte[] columns, int xCells, int zCells, float coverage) {}

    public static Result fromFaces(ByteBuffer faces, int x0, int z0, int x1, int z1,
            int lod, float worldBaseY, int cameraGridY, float cameraX, float cameraZ,
            List<CpuCloudGenerator.CloudLayerGroup> groups,
            List<CpuCloudGenerator.RegionMask> regions) {
        if(lod<=0 || x1<=x0 || z1<=z0 || (x1-x0)%lod!=0 || (z1-z0)%lod!=0)
            throw new IllegalArgumentException("Invalid storm column bounds");
        int xCells=(x1-x0)/lod,zCells=(z1-z0)/lod;
        byte[] columns=new byte[Math.multiplyExact(xCells,zCells)];
        boolean[] marked=new boolean[columns.length];
        if(faces!=null) {
            if(faces.remaining()%GpuCloudGeneration.BYTES_PER_INSTANCE!=0)
                throw new IllegalArgumentException("Invalid GPU face stride");
            for(int p=faces.position();p<faces.limit();p+=GpuCloudGeneration.BYTES_PER_INSTANCE) {
                if(faces.getFloat(p)!=3.0f) continue;
                double cx=faces.getFloat(p+4)/8.0,cy=faces.getFloat(p+8),cz=faces.getFloat(p+12)/8.0;
                if(!Double.isFinite(cx) || !Double.isFinite(cy) || !Double.isFinite(cz))
                    throw new IllegalArgumentException("Nonfinite GPU storm face");
                double lowerY=(cy-worldBaseY)/8.0-lod*.5;
                if(lowerY<=cameraGridY) continue;
                int ix=(int)Math.floor((cx-x0)/lod),iz=(int)Math.floor((cz-z0)/lod);
                if(ix<0 || ix>=xCells || iz<0 || iz>=zCells)
                    throw new IllegalArgumentException("GPU storm face outside chunk");
                int index=ix*zCells+iz;
                if(marked[index]) continue;
                int group=selectedGroup((float)cx,(float)cz,regions);
                if(group<0 || group>=groups.size())
                    throw new IllegalArgumentException("GPU face has no valid formation group");
                if(groups.get(group).stormType()) { marked[index]=true; columns[index]=1; }
            }
        }
        float coverage=StormCoverage.contribution(marked,xCells,zCells,x0,z0,lod,cameraX,cameraZ);
        return new Result(columns,xCells,zCells,coverage);
    }

    static int selectedGroup(float x,float z,List<CpuCloudGenerator.RegionMask> regions) {
        for(var r:regions) {
            float dx=x-r.x(),dz=z-r.z();
            float tx=r.m00()*dx+r.m01()*dz,tz=r.m10()*dx+r.m11()*dz;
            float distance=(float)Math.sqrt(tx*tx+tz*tz);
            if(distance<r.radius()) return r.groupIndex();
        }
        return -1;
    }
}
