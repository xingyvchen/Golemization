package com.example.copper_enchant.client.model;

import com.example.copper_enchant.client.mixin.ModelPartAccessor;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 雕像专用模型:完整克隆生物模型的部件树,但 {@code setupAnim} 为空实现。
 * <p>
 * 26.2 延迟渲染会在绘制阶段对每个模型节点再次执行 {@code setupAnim},
 * 共享模型会被覆盖导致姿态不稳定(闪烁)。雕像使用本克隆模型后,
 * 姿态完全由提交阶段写入的捕获帧骨骼旋转决定,绘制阶段不再被改写。
 */
public class StatueEntityModel<S> extends Model<S> {

    private final int partCount;

    public StatueEntityModel(ModelPart root, Function<Identifier, RenderType> renderType) {
        super(root, renderType);
        this.partCount = root.getAllParts().size();
    }

    public int partCount() {
        return partCount;
    }

    @Override
    public void setupAnim(S state) {
        // 空实现:姿态完全由捕获帧驱动
    }

    public static ModelPart clonePart(ModelPart src) {
        ModelPartAccessor accessor = (ModelPartAccessor) (Object) src;
        Map<String, ModelPart> children = new HashMap<>();
        for (Map.Entry<String, ModelPart> entry : accessor.copperEnchant$getChildren().entrySet()) {
            children.put(entry.getKey(), clonePart(entry.getValue()));
        }
        ModelPart clone = new ModelPart(accessor.copperEnchant$getCubes(), children);
        clone.x = src.x;
        clone.y = src.y;
        clone.z = src.z;
        clone.xRot = src.xRot;
        clone.yRot = src.yRot;
        clone.zRot = src.zRot;
        clone.xScale = src.xScale;
        clone.yScale = src.yScale;
        clone.zScale = src.zScale;
        // 不继承源模型的瞬时可见性:共享的全局模型可能正处于 setupAnim 中途(某部位 visible=false)。
        // 若快照此刻的隐藏状态,雕像克隆会永久缺失该部位(渲染时 ModelPart.render 遇 visible=false 直接跳过 → 透明)。
        // 可见性由渲染状态/逐帧参数驱动,骨骼回放不管可见性,故雕像需强制全身可见。
        clone.visible = true;
        clone.skipDraw = false;
        clone.setInitialPose(src.getInitialPose());
        return clone;
    }
}
