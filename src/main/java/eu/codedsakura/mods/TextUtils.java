package eu.codedsakura.mods;

import net.minecraft.text.LiteralText;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

public class TextUtils {
    public static MutableText valueRepr(String name, Text value) {
        if (value.getStyle().getColor() == null)
            return new LiteralText(name + ": ").formatted(Formatting.RESET).append(value.copy().formatted(Formatting.GOLD));
        return new LiteralText(name + ": ").formatted(Formatting.RESET).append(value);
    }
    public static MutableText valueRepr(String name, String value) {
        return valueRepr(name, new LiteralText(value).formatted(Formatting.GOLD));
    }
    public static MutableText valueRepr(String name, double value) {
        return valueRepr(name, String.format("%.2f", value));
    }
    public static MutableText valueRepr(String name, float value) {
        return valueRepr(name, String.format("%.2f", value));
    }

    public static MutableText join(List<Text> values, Text joiner) {
        MutableText out = LiteralText.EMPTY.copy();
        for (int i = 0; i < values.size(); i++) {
            out.append(values.get(i));
            if (i < values.size() - 1) out.append(joiner);
        }
        return out;
    }
}
