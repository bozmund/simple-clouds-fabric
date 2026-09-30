import dev.nonamecrackers2.simpleclouds.common.command.argument.CloudTypeArgument;
import net.minecraft.resources.Identifier;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.google.gson.JsonObject;
import java.util.ArrayList;

public class CloudArgumentWireTest {
    public static void main(String[] args) throws Exception {
        var info=new CloudTypeArgument.Info();
        var ids=new ArrayList<Identifier>(); ids.add(Identifier.parse("test:cloud"));
        var original=info.new Template(ids); ids.clear();
        var buffer=new FriendlyByteBuf(Unpooled.buffer());
        try {
            info.serializeToNetwork(original,buffer);
            var decoded=info.deserializeFromNetwork(buffer);
            if(buffer.readableBytes()!=0) throw new AssertionError("unconsumed wire data");
            var argument=decoded.instantiate(null);
            if(!argument.parse(new StringReader("test:cloud")).equals(Identifier.parse("test:cloud")))
                throw new AssertionError("allowed type lost");
            try { argument.parse(new StringReader("test:missing")); throw new AssertionError("unknown type accepted"); }
            catch(CommandSyntaxException expected) {}
            var repacked=info.unpack(argument);
            var json=new JsonObject(); info.serializeToJson(repacked,json);
            if(!json.getAsJsonArray("types").get(0).getAsString().equals("test:cloud"))
                throw new AssertionError("client-side reserialization lost type");
            buffer.clear(); info.serializeToNetwork(repacked,buffer);
            if(!info.deserializeFromNetwork(buffer).instantiate(null).parse(new StringReader("test:cloud")).equals(Identifier.parse("test:cloud")))
                throw new AssertionError("second roundtrip failed");
        } finally { buffer.release(); }
        System.out.println("PASS: cloud argument immutable snapshot, network roundtrip, validation and client reserialization");
    }
}
