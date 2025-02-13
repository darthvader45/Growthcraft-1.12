package growthcraft.milk.common.tileentity;

import growthcraft.core.shared.definition.IMultiFluidStacks;
import growthcraft.core.shared.definition.IMultiItemStacks;
import growthcraft.core.shared.fluids.FluidTest;
import growthcraft.core.shared.fluids.GrowthcraftFluidUtils;
import growthcraft.core.shared.inventory.AccesibleSlots;
import growthcraft.core.shared.inventory.GrowthcraftInternalInventory;
import growthcraft.core.shared.inventory.InventoryProcessor;
import growthcraft.core.shared.io.nbt.NBTTagStringList;
import growthcraft.core.shared.io.stream.StreamUtils;
import growthcraft.core.shared.item.ItemTest;
import growthcraft.core.shared.item.ItemUtils;
import growthcraft.core.shared.tileentity.GrowthcraftTileDeviceBase;
import growthcraft.core.shared.tileentity.component.TileHeatingComponent;
import growthcraft.core.shared.tileentity.device.DeviceFluidSlot;
import growthcraft.core.shared.tileentity.event.TileEventHandler;
import growthcraft.core.shared.tileentity.feature.IItemOperable;
import growthcraft.core.shared.tileentity.feature.ITileHeatedDevice;
import growthcraft.core.shared.tileentity.feature.ITileNamedFluidTanks;
import growthcraft.core.shared.tileentity.feature.ITileProgressiveDevice;
import growthcraft.milk.GrowthcraftMilk;
import growthcraft.milk.common.tileentity.cheesevat.CheeseVatState;
import growthcraft.milk.shared.MilkRegistry;
import growthcraft.milk.shared.config.GrowthcraftMilkConfig;
import growthcraft.milk.shared.definition.ICheeseType;
import growthcraft.milk.shared.events.EventCheeseVat.EventCheeseVatMadeCheeseFluid;
import growthcraft.milk.shared.events.EventCheeseVat.EventCheeseVatMadeCurds;
import growthcraft.milk.shared.fluids.MilkFluidTags;
import growthcraft.milk.shared.init.GrowthcraftMilkFluids;
import growthcraft.milk.shared.init.GrowthcraftMilkItems;
import growthcraft.milk.shared.init.GrowthcraftMilkItems.SimpleCheeseTypes;
import growthcraft.milk.shared.processing.cheesevat.ICheeseVatRecipe;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TileEntityCheeseVat extends GrowthcraftTileDeviceBase implements ITickable, IItemOperable, ITileHeatedDevice, ITileNamedFluidTanks, ITileProgressiveDevice {
    public static enum FluidTankType {
        PRIMARY,
        RENNET,
        WASTE,
        RECIPE;

        public static final FluidTankType[] VALUES = new FluidTankType[]{PRIMARY, RENNET, WASTE, RECIPE};
        public final int id;
        public final String name;

        private FluidTankType() {
            this.id = ordinal();
            this.name = name().toLowerCase(Locale.ENGLISH);
        }

        public String getUnlocalizedName() {
            return "grcmilk.cheese_vat.fluid_tank." + name;
        }
    }

    private static FluidTankType[] recipeTanks = {FluidTankType.PRIMARY, FluidTankType.RECIPE};
    private static AccesibleSlots accessibleSlots = new AccesibleSlots(new int[][]{
            {0, 1, 2},
            {0, 1, 2},
            {0, 1, 2},
            {0, 1, 2},
            {0, 1, 2},
            {0, 1, 2}
    });

    private DeviceFluidSlot primaryFluidSlot = new DeviceFluidSlot(this, FluidTankType.PRIMARY.id);
    private DeviceFluidSlot rennetFluidSlot = new DeviceFluidSlot(this, FluidTankType.RENNET.id);
    private DeviceFluidSlot wasteFluidSlot = new DeviceFluidSlot(this, FluidTankType.WASTE.id);
    private boolean recheckRecipe;
    private TileHeatingComponent heatComponent = new TileHeatingComponent(this, 0.5f);
    private CheeseVatState vatState = CheeseVatState.IDLE;
    private float progress;
    private int progressMax;

    public boolean isIdle() {
        return vatState == CheeseVatState.IDLE;
    }

    public boolean isWorking() {
        return !isIdle();
    }

    private void setVatState(CheeseVatState state) {
        this.vatState = state;
        markDirty();
    }

    public String getVatState() {
        return this.vatState.name();
    }

    private void goIdle() {
        setVatState(CheeseVatState.IDLE);
    }

    private void setupProgress(int value) {
        this.progress = 0;
        this.progressMax = value;
    }

    private void resetProgress() {
        setupProgress(0);
    }

    @Override
    public float getDeviceProgress() {
        if (progressMax > 0) {
            return progress / (float) progressMax;
        }
        return 0.0f;
    }

    @Override
    public int getDeviceProgressScaled(int scale) {
        if (progressMax > 0) {
            return (int) (progress * scale / progressMax);
        }
        return 0;
    }

    @Override
    public boolean isHeated() {
        return heatComponent.getHeatMultiplier() > 0;
    }

    @Override
    public float getHeatMultiplier() {
        return heatComponent.getHeatMultiplier();
    }

    @Override
    public int getHeatScaled(int scale) {
        return (int) (scale * MathHelper.clamp(getHeatMultiplier(), 0f, 1f));
    }

    public void markForRecipeCheck() {
        this.recheckRecipe = true;
    }

    @Override
    public void onInventoryChanged(IInventory inv, int index) {
        super.onInventoryChanged(inv, index);
        markForRecipeCheck();
    }

    @Override
    protected FluidTank[] createTanks() {
        return new FluidTank[]{
                // milk
                new FluidTank(GrowthcraftMilkConfig.cheeseVatPrimaryTankCapacity),
                // rennet
                new FluidTank(GrowthcraftMilkConfig.cheeseVatRennetTankCapacity),
                // waste
                new FluidTank(GrowthcraftMilkConfig.cheeseVatWasteTankCapacity),
                // recipe fluid
                new FluidTank(GrowthcraftMilkConfig.cheeseVatRecipeTankCapacity)
        };
    }

    public int getVatFluidCapacity() {
        return getFluidTank(FluidTankType.PRIMARY.id).getCapacity() +
                getFluidTank(FluidTankType.WASTE.id).getCapacity() +
                getFluidTank(FluidTankType.RECIPE.id).getCapacity();
    }

    @Override
    public GrowthcraftInternalInventory createInventory() {
        return new GrowthcraftInternalInventory(this, 3, 1);
    }

    @Override
    public String getDefaultInventoryName() {
        return "container.grcmilk.CheeseVat";
    }

    @Override
    public boolean isItemValidForSlot(int index, ItemStack itemstack) {
        return true;
    }

    @Override
    public int[] getSlotsForFace(EnumFacing side) {
        return accessibleSlots.slotsAt(side);
    }

    @Override
    public boolean canExtractItem(int index, ItemStack stack, EnumFacing side) {
        return accessibleSlots.sideContains(side, index);
    }

    private boolean activateCurdTransition(boolean checkOnly) {
        final ItemStack starterCultureStack = GrowthcraftMilkItems.starterCulture.asStack();
        final int slot = InventoryProcessor.instance().findItemSlot(this, starterCultureStack);
        if (slot < 0) {
            GrowthcraftMilk.logger.debug("No Starter Culture found!");
            return false;
        }

        final FluidStack milkStack = primaryFluidSlot.get();
        if (!FluidTest.hasTags(milkStack, MilkFluidTags.MILK)) {
            GrowthcraftMilk.logger.debug("Primary Fluid is NOT milk.");
            return false;
        }
        if (!primaryFluidSlot.isFull()) {
            GrowthcraftMilk.logger.debug("Primary Fluid Tank is NOT full.");
            return false;
        }

        final FluidStack rennetStack = rennetFluidSlot.get();
        if (!FluidTest.hasTags(rennetStack, MilkFluidTags.RENNET)) {
            GrowthcraftMilk.logger.debug("Rennet contains NON rennet fluid.");
            return false;
        }
        if (!rennetFluidSlot.isFull()) {
            GrowthcraftMilk.logger.debug("Rennet Fluid Tank is NOT full.");
            return false;
        }

        if (!checkOnly) {
            decrStackSize(slot, 1);
            primaryFluidSlot.set(GrowthcraftFluidUtils.exchangeFluid(milkStack, GrowthcraftMilkFluids.curds.getFluid()));
            rennetFluidSlot.clear();
            wasteFluidSlot.fill(GrowthcraftMilkFluids.whey.asFluidStack(GrowthcraftMilkConfig.cheeseVatMilkToCurdsWheyAmount), true);
            GrowthcraftMilk.MILK_BUS.post(new EventCheeseVatMadeCurds(this));
        }
        return true;
    }

    private boolean activateWheyTransition(boolean checkOnly) {
        final FluidStack milkStack = primaryFluidSlot.get();
        if (FluidTest.hasTags(milkStack, MilkFluidTags.WHEY) && primaryFluidSlot.isFull()) {
            if (!checkOnly) {
                final Fluid fluid = SimpleCheeseTypes.RICOTTA.getFluids().getFluid(); // GrowthcraftMilkFluids.cheeses.get(EnumCheeseType.RICOTTA).getFluid();
                primaryFluidSlot.set(GrowthcraftFluidUtils.exchangeFluid(primaryFluidSlot.get(), fluid));
                wasteFluidSlot.fill(GrowthcraftMilkFluids.whey.asFluidStack(GrowthcraftMilkConfig.cheeseVatWheyToRicottaWheyAmount), true);
                GrowthcraftMilk.MILK_BUS.post(new EventCheeseVatMadeCheeseFluid(this));
            }
            return true;
        }
        return false;
    }

    private boolean commitMilkCurdRecipe(boolean checkOnly) {
        final List<FluidStack> fluids = new ArrayList<FluidStack>();
        final List<ItemStack> items = new ArrayList<ItemStack>();
        for (FluidTankType t : recipeTanks) {
            final FluidStack stack = getFluidStack(t.id);
            if (FluidTest.isValid(stack)) fluids.add(stack);
        }

        for (int i = 0; i < getSizeInventory(); ++i) {
            final ItemStack stack = getStackInSlot(i);
            if (ItemUtils.isEmpty(stack)) break;
            items.add(stack);
        }

        final ICheeseVatRecipe recipe = MilkRegistry.instance().cheeseVat().findRecipe(fluids, items);
        if (recipe != null) {
            final List<IMultiItemStacks> inputItems = recipe.getInputItemStacks();
            final List<IMultiFluidStacks> inputFluids = recipe.getInputFluidStacks();
            // locate all the items in the inventory
            final int[] invSlots = InventoryProcessor.instance().findItemSlots(this, inputItems);
            if (InventoryProcessor.instance().slotsAreValid(this, invSlots) &&
                    InventoryProcessor.instance().checkSlotsAndSizes(this, inputItems, invSlots)) {
                if (FluidTest.hasEnoughAndExpected(inputFluids, fluids)) {
                    if (!checkOnly) {
                        // consume items
                        InventoryProcessor.instance().consumeItemsInSlots(this, inputItems, invSlots);
                        // consume all fluids
                        for (int fluidIndex = 0; fluidIndex < fluids.size(); ++fluidIndex) {
                            final FluidStack fluidStack = fluids.get(fluidIndex);
                            final FluidTankType t = recipeTanks[fluidIndex];
                            if (fluidStack != null) {
                                drainFluidTank(t.id, fluidStack.amount, true);
                            }
                        }
                        // spawn output item stacks
                        for (ItemStack stack : recipe.getOutputItemStacks()) {
                            if (!ItemUtils.isEmpty(stack)) {
                                ItemUtils.spawnItemStackAtTile(stack.copy(), this, world.rand);
                            }
                        }
                        // fill output fluid tanks
                        int tankIndex = 0;
                        for (FluidStack stack : recipe.getOutputFluidStacks()) {
                            if (stack != null) {
                                fillFluidTank(tankIndex, stack.copy(), true);
                            }
                            tankIndex++;
                            // Currently the cheese vat does not support more than 1 fluid output.
                            break;
                        }
                        // mark vat for block update
                        markDirty();
                        // post event to bus
                        GrowthcraftMilk.MILK_BUS.post(new EventCheeseVatMadeCheeseFluid(this));
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private void commitRecipe() {
        final FluidStack stack = primaryFluidSlot.get();
        if (FluidTest.hasTags(stack, MilkFluidTags.MILK_CURDS)) {
            if (commitMilkCurdRecipe(true)) {
                setupProgress(GrowthcraftMilkConfig.cheeseVatCheeseTime);
                setVatState(CheeseVatState.PREPARING_CHEESE);
            }
        }
    }

    private void onFinishedProgress() {
        switch (vatState) {
            case PREPARING_RICOTTA:
                activateWheyTransition(false);
                break;
            case PREPARING_CURDS:
                activateCurdTransition(false);
                break;
            case PREPARING_CHEESE:
                commitMilkCurdRecipe(false);
                break;
            default:
        }
        resetProgress();
    }

    @Override
    public void update() {
        if (!world.isRemote) {
            heatComponent.update();
            if (!isIdle()) {
                if (isHeated()) {
                    if (progress < progressMax) {
                        progress += 1 * getHeatMultiplier();
                    } else {
                        onFinishedProgress();
                        goIdle();
                    }
                } else {
                    if (progress > 0) {
                        progress -= 1;
                    } else {
                        goIdle();
                    }
                }
            } else {
                if (recheckRecipe) {
                    this.recheckRecipe = false;
                    if (isHeated()) commitRecipe();
                }
            }
        }
    }

    private boolean primaryTankHasMilk() {
        return FluidTest.hasTags(primaryFluidSlot.get(), MilkFluidTags.MILK);
    }

    private boolean primaryTankHasCurds() {
        return FluidTest.hasTags(primaryFluidSlot.get(), MilkFluidTags.MILK_CURDS);
    }

    @Override
    public boolean canFill(EnumFacing from, Fluid fluid) {
        return FluidTest.hasTags(fluid, MilkFluidTags.MILK) ||
                FluidTest.hasTags(fluid, MilkFluidTags.WHEY) ||
                FluidTest.hasTags(fluid, MilkFluidTags.RENNET) ||
                MilkRegistry.instance().cheeseVat().isFluidIngredient(fluid);
    }

    @Override
    protected FluidStack doDrain(EnumFacing dir, int amount, boolean doDrain) {
        if (!isIdle()) return null;
        return wasteFluidSlot.consume(amount, doDrain);
    }

    @Override
    protected FluidStack doDrain(EnumFacing dir, FluidStack stack, boolean doDrain) {
        if (!FluidTest.areStacksEqual(wasteFluidSlot.get(), stack)) return null;
        return doDrain(dir, stack.amount, doDrain);
    }

    @Override
    protected int doFill(EnumFacing dir, FluidStack stack, boolean doFill) {
        if (!isIdle()) return 0;
        int result = 0;

        if (FluidTest.hasTags(stack, MilkFluidTags.MILK) || FluidTest.hasTags(stack, MilkFluidTags.WHEY)) {
            result = primaryFluidSlot.fill(stack, doFill);
        } else if (FluidTest.isValidAndExpected(GrowthcraftMilkFluids.rennet.getFluid(), stack)) {
            if (primaryTankHasMilk()) {
                result = rennetFluidSlot.fill(stack, doFill);
            }
        } else if (MilkRegistry.instance().cheeseVat().isFluidIngredient(stack)) {
            if (primaryTankHasCurds()) {
                result = fillFluidTank(FluidTankType.RECIPE.id, stack, doFill);
            }
        }
        return result;
    }

    private void playSuccessfulSwordActivationFX() {
        // world.playSoundEffect((double)xCoord, (double)yCoord, (double)zCoord, "random.successful_hit", 0.3f, 0.5f);
        world.playSound((double) pos.getX(), (double) pos.getY(), (double) pos.getZ(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.BLOCKS, 0.3f, 0.5f, false);
    }

    private boolean doSwordActivation(EntityPlayer _player, ItemStack _stack) {
        if (!isHeated()) {
            GrowthcraftMilk.logger.debug("Vat is NOT heated.");
            return false;
        }
        GrowthcraftMilk.logger.debug("Activating Using Sword.");
        final FluidStack milkStack = primaryFluidSlot.get();
        if (FluidTest.hasTags(milkStack, MilkFluidTags.MILK)) {
            GrowthcraftMilk.logger.debug("Activating Curd Transition.");
            if (activateCurdTransition(true)) {
                setupProgress(GrowthcraftMilkConfig.cheeseVatCurdTime);
                setVatState(CheeseVatState.PREPARING_CURDS);
                playSuccessfulSwordActivationFX();
                return true;
            }
        } else if (FluidTest.hasTags(milkStack, MilkFluidTags.WHEY)) {
            GrowthcraftMilk.logger.debug("Activating Whey Transition.");
            if (activateWheyTransition(true)) {
                setupProgress(GrowthcraftMilkConfig.cheeseVatWheyTime);
                setVatState(CheeseVatState.PREPARING_RICOTTA);
                playSuccessfulSwordActivationFX();
                return true;
            }
        }
        return false;
    }

    private boolean collectCurdInCheeseCloth(EntityPlayer player, ItemStack stack) {
        final FluidStack fluidStack = primaryFluidSlot.get();
        if (FluidTest.hasTags(fluidStack, MilkFluidTags.CHEESE)) {
            final Fluid fluid = fluidStack.getFluid();
            final ICheeseType type = MilkRegistry.instance().cheese().getCheeseByFluid(fluid); // GrowthcraftMilkFluids.fluidToCheeseType.get(fluid);

            if (type != null) {
                primaryFluidSlot.clear();
                ItemUtils.decrPlayerCurrentInventorySlot(player, 1);
                final ItemStack curdItemStack = type.getCurdBlocks().asStack();
                ItemUtils.addStackToPlayer(curdItemStack, player, false);
                return true;
            }
        }
        return false;
    }

    private boolean addItemIngredient(EntityPlayer player, ItemStack stack) {
        final int slot = InventoryProcessor.instance().findNextEmpty(this);
        if (slot == -1) return false;
        final ItemStack result = ItemUtils.decrPlayerCurrentInventorySlot(player, 1);
        setInventorySlotContents(slot, result);
        return true;
    }

    @Override
    public boolean tryPlaceItem(IItemOperable.Action action, EntityPlayer player, ItemStack stack) {
        if (IItemOperable.Action.RIGHT != action) return false;
        if (!isIdle()) return false;
        if (!ItemTest.isValid(stack)) return false;
        final Item item = stack.getItem();
        if (item instanceof ItemSword) {
            return doSwordActivation(player, stack);
        } else if (GrowthcraftMilkItems.starterCulture.equals(item)) {
            return addItemIngredient(player, stack);
        } else if (GrowthcraftMilkItems.cheeseCloth.equals(item)) {
            return collectCurdInCheeseCloth(player, stack);
        } else if (MilkRegistry.instance().cheeseVat().isItemIngredient(stack)) {
            return addItemIngredient(player, stack);
        }
        return false;
    }

    @Override
    public boolean tryTakeItem(IItemOperable.Action action, EntityPlayer player, ItemStack onHand) {
        if (IItemOperable.Action.RIGHT != action) return false;
        if (!isIdle()) return false;
        if (ItemUtils.isEmpty(onHand)) {
            final int slot = InventoryProcessor.instance().findNextPresentFromEnd(this);
            if (slot == -1) return false;
            final ItemStack stack = InventoryProcessor.instance().yankSlot(this, slot);
            //ItemUtils.addStackToPlayer(stack, player, false);
            ItemUtils.spawnItemStackAtEntity(stack, player, world.rand);
            return true;
        }
        return false;
    }

    @Override
    protected void markFluidDirty() {
        markForRecipeCheck();
        markDirtyAndUpdate();
    }

    @Override
    public void writeFluidTankNamesToTag(NBTTagCompound tag) {
        final NBTTagStringList tagList = new NBTTagStringList();
        for (FluidTankType type : FluidTankType.VALUES) {
            tagList.add(type.getUnlocalizedName());
        }
        tag.setTag("tank_names", tagList.getTag());
    }

    @TileEventHandler(event = TileEventHandler.EventType.NBT_READ)
    public void readFromNBT_CheeseVat(NBTTagCompound nbt) {
        if (nbt.hasKey("progress_max")) {
            this.progressMax = nbt.getInteger("progress_max");
        }
        if (nbt.hasKey("progress")) {
            this.progress = nbt.getFloat("progress");
        }
        heatComponent.readFromNBT(nbt, "heat_component");
        this.vatState = CheeseVatState.getStateSafe(nbt.getString("vat_state"));
    }

    @TileEventHandler(event = TileEventHandler.EventType.NBT_WRITE)
    public void writeToNBT_CheeseVat(NBTTagCompound nbt) {
        nbt.setInteger("progress_max", progressMax);
        nbt.setFloat("progress", progress);
        heatComponent.writeToNBT(nbt, "heat_component");
        nbt.setString("vat_state", vatState.name);
    }

    @TileEventHandler(event = TileEventHandler.EventType.NETWORK_READ)
    public boolean readFromStream_CheeseVat(ByteBuf stream) throws IOException {
        this.progressMax = stream.readInt();
        this.progress = stream.readFloat();
        heatComponent.readFromStream(stream);
        String name = "idle";
//		try
//		{
        name = StreamUtils.readStringASCII(stream);
//		}
//		catch (UnsupportedEncodingException ex)
//		{
//			ex.printStackTrace();
//		}
        this.vatState = CheeseVatState.getStateSafe(name);
        return false;
    }

    @TileEventHandler(event = TileEventHandler.EventType.NETWORK_WRITE)
    public boolean writeToStream_CheeseVat(ByteBuf stream) throws IOException {
        stream.writeInt(progressMax);
        stream.writeFloat(progress);
        heatComponent.writeToStream(stream);
//		try
//		{
        StreamUtils.writeStringASCII(stream, vatState.name);
//		}
//		catch (UnsupportedEncodingException ex)
//		{
//			ex.printStackTrace();
//		}
        return false;
    }

    public int calcRedstone() {
        return getFluidAmountScaled(15, FluidTankType.PRIMARY.id);
    }
}
