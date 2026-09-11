package dev.nonamecrackers2.simpleclouds.client.renderer.v2;

/**
 * One rain drop for the 26.2 rain pass (the 1.20.1 PrecipitationQuad's render
 * data): world-space anchor (top of the drop), straight-down length in blocks,
 * width in blocks (already fade-ramped), and the scrolling texture offset.
 */
public record WorldEffectsDrop(float x, float y, float z, float length, float width, float uvOffset)
{
}
