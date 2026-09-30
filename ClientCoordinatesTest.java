import com.mojang.brigadier.StringReader;
import net.minecraft.commands.arguments.coordinates.Vec2Argument;
import net.minecraft.commands.arguments.coordinates.WorldCoordinates;
import net.minecraft.world.phys.Vec3;
import dev.nonamecrackers2.simpleclouds.common.command.CloudCommandSource;

public class ClientCoordinatesTest {
    static void check(String text, boolean centered, float x, float z) throws Exception {
        var parsed=(WorldCoordinates)Vec2Argument.vec2(centered).parse(new StringReader(text));
        var result=CloudCommandSource.resolveClientVec2(parsed,new Vec3(100.25,64,-20.75));
        if(result.x!=x || result.y!=z) throw new AssertionError(text+": "+result.x+","+result.y);
    }
    public static void main(String[] args) throws Exception {
        check("~ ~",true,100.25f,-20.75f);
        check("~2 ~-3",true,102.25f,-23.75f);
        check("10 -4",true,10.5f,-3.5f);
        check("10 -4",false,10,-4);
        check("10.25 ~1.5",true,10.25f,-19.25f);
        System.out.println("PASS: relative, absolute, mixed and centered client coordinates");
    }
}
