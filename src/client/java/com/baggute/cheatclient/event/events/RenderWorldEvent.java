package com.baggute.cheatclient.event.events;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;

public final class RenderWorldEvent {
    private final PoseStack poseStack;
    private final float tickDelta;
    private final Matrix4f projection;

    public RenderWorldEvent(PoseStack poseStack, float tickDelta, Matrix4f projection) {
        this.poseStack = poseStack;
        this.tickDelta = tickDelta;
        this.projection = projection;
    }

    public PoseStack getPoseStack() {
        return poseStack;
    }

    public float getTickDelta() {
        return tickDelta;
    }

    public Matrix4f getProjection() {
        return projection;
    }
}