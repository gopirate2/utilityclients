package example;

import com.baggute.cheatclient.api.ModuleProvider;
import com.baggute.cheatclient.module.Module;
import com.baggute.cheatclient.module.Category;
import com.baggute.cheatclient.settings.BoolSetting;
import com.baggute.cheatclient.module.misc.Notifications;
import java.util.List;

public final class HelloProvider implements ModuleProvider {
    public java.util.Collection<? extends Module> createModules() { return List.of(new HelloModule()); }
    public static final class HelloModule extends Module {
        private final BoolSetting greeting = new BoolSetting("Greeting", "Show a local notification when enabled", true);
        public HelloModule() {
            super("HelloCustom", "A working custom module. Bind a key and toggle it.", Category.MISC);
            addSetting(greeting);
        }
        @Override protected void onEnable() {
            if (greeting.get()) Notifications.push("Hello from your custom module", true);
        }
    }
}
