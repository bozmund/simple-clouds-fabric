// Bounds are world-block XZ of half-open cloud-cell coverage. Zero bounds
// disable clipping for existing callers and the previewer. Select by the
// instance CENTER, not each quad vertex: never cut a voxel's side in half.
layout(std140) uniform CloudClip {
    vec4 CellClipBounds;
};

bool cloudCellInsideClip(vec2 worldCenter)
{
    return CellClipBounds.z <= CellClipBounds.x
        || (all(greaterThanEqual(worldCenter, CellClipBounds.xy))
            && all(lessThan(worldCenter, CellClipBounds.zw)));
}
