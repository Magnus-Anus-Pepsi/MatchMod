package com.matchmode;

import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;

@Mod("matchmode")
public class MatchModeMod {
    public MatchModeMod() {
        ModLoadingContext.get().registerExtensionPoint(
            IExtensionPoint.DisplayTest.class,
            () -> new IExtensionPoint.DisplayTest(
                () -> "OHNOES",
                (remote, isServer) -> true
            )
        );
    }
}
