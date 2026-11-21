package com.baggute.cheatclient.gui.widget;

import net.minecraft.client.gui.GuiGraphicsExtractor;

public abstract class Widget {
    protected int x;
    protected int y;
    protected int width;
    protected int height;

    protected Widget(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public abstract void render(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float delta
    );

    public abstract boolean mouseClicked(double mx, double my, int button);

    public abstract boolean keyPressed(int keyCode, int scanCode, int modifiers);

    public void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public boolean contains(double mx, double my) {
        return mx >= x && mx < x + width && my >= y && my < y + height;
    }
}