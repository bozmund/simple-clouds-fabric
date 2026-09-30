import com.mojang.blaze3d.platform.InputConstants;
import dev.nonamecrackers2.simpleclouds.mixin.MixinKeyMapping;

public class KeyMappingMigrationTest {
    public static void main(String[] args) throws Exception {
        var method = MixinKeyMapping.class.getDeclaredMethod("simpleclouds$migrateLegacyUnbound", InputConstants.Key.class);
        method.setAccessible(true);
        var old = InputConstants.Type.KEYBOARD.getOrCreate(-1);
        var valid = InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_F12);
        var mouse = InputConstants.Type.MOUSE.getOrCreate(-1);
        for (String name : new String[]{"simpleclouds.key.openConfig", "another.mod.key"}) {
            var target = new MixinKeyMapping() { public String getName() { return name; } };
            for (var key : new InputConstants.Key[]{old, valid, mouse, InputConstants.UNKNOWN}) {
                Object expected = name.equals("simpleclouds.key.openConfig") && key == old ? InputConstants.UNKNOWN : key;
                if (method.invoke(target, key) != expected) throw new AssertionError(name + ": " + key);
            }
        }
        System.out.println("PASS legacy config key migration; valid and unrelated bindings preserved");
    }
}
