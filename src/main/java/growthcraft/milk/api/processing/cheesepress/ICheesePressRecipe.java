package growthcraft.milk.api.processing.cheesepress;

import net.minecraft.item.ItemStack;

public interface ICheesePressRecipe {

	ItemStack getInputItemStack();
	ItemStack getOutputItemStack();
	int getTimeMax();
	boolean isMatchingRecipe(ItemStack stack);

}
