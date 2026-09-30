package com.matchmode;

import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;

@Mod("matchmode")
public class MatchModeMod {
    public MatchModeMod() {
        // Мод нужен только серверу: клиенты без него смогут подключиться
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
            () -> new IExtensionPoint.DisplayTest(
                () -> IExtensionPoint.DisplayTest.IGNORESERVERONLY,
                (remote, isServer) -> true));
    }
}
