package com.example.copper_enchant.client.mixin;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.PufferfishRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露河豚渲染器的三套(小/中/大)几何模型。
 * <p>河豚的膨胀程度由渲染器按自身字段选模型决定,雕像回放必须据此选对几何,
 * 否则冻结姿态的膨涨尺寸(look 的 small/mid/big)会因实时随机而错乱。
 */
@Mixin(PufferfishRenderer.class)
public interface PufferfishRendererAccessor {
    @Accessor("small")
    EntityModel getSmallModel();

    @Accessor("mid")
    EntityModel getMidModel();

    @Accessor("big")
    EntityModel getBigModel();
}