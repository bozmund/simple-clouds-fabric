package nonamecrackers2.crackerslib.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;

/**
 * 26.2 port: uses ConfirmLinkScreen.confirmLinkNow helper.
 */
public class GUIUtils
{
	public static void openLink(String link)
	{
		Minecraft mc = Minecraft.getInstance();
		ConfirmLinkScreen.confirmLinkNow(mc.gui.screen(), link, true);
	}
}
