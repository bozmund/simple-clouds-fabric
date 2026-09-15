// Base quad lies on x=-1. Face ids match the CPU/compute neighbor order:
// -X, +X, -Y, +Y, -Z, +Z. Keep all passes on the same rotation contract.
vec3 applySideTransform(vec3 p, int side)
{
    if (side == 0) return p;
    if (side == 1) return vec3(-p.x, p.y, -p.z);
    if (side == 2) return vec3(-p.y, p.x, p.z);
    if (side == 3) return vec3(p.y, -p.x, p.z);
    if (side == 4) return vec3(-p.z, p.y, p.x);
    return vec3(p.z, p.y, -p.x);
}
