package com.realhanabimod.blockentity;

import com.realhanabimod.data.HanabiShowData;
import com.realhanabimod.init.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class HanabiLauncherBlockEntity extends BlockEntity {

    private final HanabiShowData showData = new HanabiShowData();

    public HanabiLauncherBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HANABI_LAUNCHER.get(), pos, state);
    }

    public HanabiShowData getShowData() {
        return showData;
    }

    public void setShowData(HanabiShowData data) {
        showData.items.clear();
        showData.items.addAll(data.items);
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Show", showData.writeNbt());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("Show")) {
            showData.readNbt(tag.getCompound("Show"));
        }
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        tag.put("Show", showData.writeNbt());
        return tag;
    }
}
