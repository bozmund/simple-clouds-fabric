package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Original crackerslib Screen3D transforms; no world-camera perspective.
 * Projection changes only the depth convention to modern reverse-Z [0,1]. */
public final class OriginalPreviewTransform {
    private OriginalPreviewTransform() {}
    public static Matrix4f view(int guiWidth,int guiHeight,float zoom,float zoomConstant,
            float farPlane,float rotationX,float rotationY,Vector3f offset) {
        check(guiWidth,guiHeight,farPlane);
        if(!Float.isFinite(zoom) || zoom<=0 || !Float.isFinite(zoomConstant) || zoomConstant<=0
                || !Float.isFinite(rotationX) || !Float.isFinite(rotationY) || !offset.isFinite())
            throw new IllegalArgumentException("Invalid original preview camera");
        Quaternionf rotation=new Quaternionf().rotateX((float)Math.toRadians(rotationX))
            .rotateY((float)Math.PI+(float)Math.toRadians(rotationY));
        // Original uses integer Screen.width/2, not projectionWidth/2.
        return new Matrix4f().translation(guiWidth/2,guiHeight/2,0)
            .scale(zoom*zoomConstant,zoom*zoomConstant,-1)
            .translate(0,0,farPlane/2).rotate(rotation).translate(offset);
    }
    public static Matrix4f projection(float width,float height,float farPlane) {
        check(width,height,farPlane);
        return new Matrix4f().setOrtho(0,width,height,0,farPlane,0,true);
    }
    private static void check(float width,float height,float farPlane) {
        if(!Float.isFinite(width) || width<=0 || !Float.isFinite(height) || height<=0
                || !Float.isFinite(farPlane) || farPlane<=0)
            throw new IllegalArgumentException("Invalid original preview dimensions");
    }
}
