package com.example.copper_enchant.block;

import com.example.copper_enchant.blockentity.CopperStatueBlockEntity;
import com.example.copper_enchant.registry.ModEnchantments;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.Optional;

public class CopperStatueBlock extends BaseEntityBlock implements SimpleWaterloggedBlock {

    public static final MapCodec<CopperStatueBlock> CODEC = RecordCodecBuilder.mapCodec(instance ->
        instance.group(
            BlockBehaviour.propertiesCodec()
        ).apply(instance, CopperStatueBlock::new)
    );

    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    private static final VoxelShape SHAPE = Shapes.box(0.25, 0.0, 0.25, 0.75, 1.0, 0.75);

    public CopperStatueBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.defaultBlockState()
            .setValue(FACING, Direction.NORTH)
            .setValue(WATERLOGGED, false));
    }

    @Override
    public MapCodec<? extends CopperStatueBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, WATERLOGGED);
    }

    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        return this.defaultBlockState()
            .setValue(FACING, context.getHorizontalDirection().getOpposite())
            .setValue(WATERLOGGED, context.getLevel().getFluidState(context.getClickedPos()).getType() == Fluids.WATER);
    }

    @Override
    protected BlockState rotate(BlockState state, net.minecraft.world.level.block.Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, net.minecraft.world.level.block.Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected boolean isPathfindable(BlockState state, net.minecraft.world.level.pathfinder.PathComputationType type) {
        return false;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CopperStatueBlockEntity(pos, state);
    }

    @Override
    protected boolean shouldChangedStateKeepBlockEntity(BlockState state) {
        return true;
    }

    /** 从方块物品放置时,把块实体数据(帧)在接下来一 tick 推给客户端。
     *  否则客户端只收到方块状态、收不到帧数据,"被放出来后帧数会没有"。 */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel
                && level.getBlockEntity(pos) instanceof CopperStatueBlockEntity be) {
            be.schedulePlacementDataSync();
        }
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                          Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (level instanceof ServerLevel serverLevel) {
            if (isGolemizationSword(stack, serverLevel)
                    && level.getBlockEntity(pos) instanceof CopperStatueBlockEntity be) {
                be.rotateClockwise(10.0f);
                level.playSound(null, pos, SoundEvents.COPPER_BREAK, SoundSource.BLOCKS, 1.0f, 1.2f);
                player.swing(hand);
                return InteractionResult.SUCCESS;
            }

            if (stack.is(Items.WOODEN_SWORD)
                    && level.getBlockEntity(pos) instanceof CopperStatueBlockEntity be) {
                InteractionResult result =
                    com.example.copper_enchant.interaction.StatueFrameInteraction
                        .onWoodenSwordClick(serverLevel, pos, be, player, hand);
                return result;
            }

            if (stack.getItem() instanceof AxeItem
                    && level.getBlockEntity(pos) instanceof CopperStatueBlockEntity be) {
                if (be.restore(serverLevel, pos)) {
                    level.playSound(null, pos, SoundEvents.COPPER_BREAK, SoundSource.BLOCKS, 1.0f, 1.0f);
                    if (!player.isCreative()) stack.hurtAndBreak(1, player, hand);
                    return InteractionResult.SUCCESS;
                }
            }
        }
        return InteractionResult.PASS;
    }

    private static boolean isGolemizationSword(ItemStack stack, Level level) {
        if (!stack.is(Items.COPPER_SWORD)) return false;
        Optional<Holder.Reference<Enchantment>> enchantment = level.registryAccess()
            .lookupOrThrow(Registries.ENCHANTMENT)
            .get(ModEnchantments.COPPERIZATION);
        if (enchantment.isEmpty()) return false;
        ItemEnchantments enchants = EnchantmentHelper.getEnchantmentsForCrafting(stack);
        return enchants.getLevel(enchantment.get()) > 0;
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level,
                                      net.minecraft.world.level.ScheduledTickAccess scheduledTickAccess,
                                      BlockPos pos, Direction direction, BlockPos neighborPos,
                                      BlockState neighborState, net.minecraft.util.RandomSource random) {
        if (state.getValue(WATERLOGGED)) {
            scheduledTickAccess.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        return super.updateShape(state, level, scheduledTickAccess, pos, direction, neighborPos, neighborState, random);
    }
}
