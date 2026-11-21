package com.baggute.cheatclient.module.player;

import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.settings.BoolSetting;

/**
 * Shift-drag quick-move across container slots.
 *
 * The buttons and modifier requirement are consumed by ContainerMouseTweaksMixin.
 */
public final class MouseTweaks extends Module {
    private final BoolSetting requireShift = new BoolSetting("requireShift", "Require Shift for drag actions", true);
    private final BoolSetting leftDrag = new BoolSetting("leftDrag", "Left-button drag quick-move", true);
    private final BoolSetting rightDrag = new BoolSetting("rightDrag", "Right-button drag pickup", true);

    public MouseTweaks() {
        super("MouseTweaks", "Shift-drag to quick-move items across container slots.", Category.PLAYER);
        addSetting(requireShift);
        addSetting(leftDrag);
        addSetting(rightDrag);
    }

    public boolean requiresShift() { return requireShift.get(); }
    public boolean allowsButton(int button) { return button == 0 ? leftDrag.get() : button == 1 && rightDrag.get(); }
}
