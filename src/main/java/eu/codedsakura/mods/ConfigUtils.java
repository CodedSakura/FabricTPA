package eu.codedsakura.mods;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.TranslatableText;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.util.List;
import java.util.Properties;
import java.util.function.Predicate;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

@SuppressWarnings({"rawtypes", "unchecked"}) // :'(
public class ConfigUtils {
    public List<IConfigValue> values;
    private final File file;
    private final Logger logger;

    public ConfigUtils(File file, Logger logger, List<IConfigValue> values) {
        this.file = file;
        this.logger = logger;
        this.values = values;
        this.read();
        this.save();
    }

    public void read() {
        Properties props = new Properties();
        try (InputStream input = new FileInputStream(file)) {
            logger.debug("Reading config...");
            props.load(input);
            this.values.forEach(value -> value.value = value.getFromProps(props));
        } catch (FileNotFoundException ignored) {
            logger.debug("Initialising config...");
            this.values.forEach(value -> value.value = value.defaultValue);
        } catch (IOException e) {
            logger.fatal("Failed to load config file!");
            e.printStackTrace();
        } catch (Exception e) {
            logger.fatal("Failed to parse the config!");
            e.printStackTrace();
        }
    }
    public void save() {
        Properties props = new Properties();
        this.values.forEach(value -> value.setToProps(props));
        logger.debug("Updating config...");
        try (OutputStream output = new FileOutputStream(file)) {
            props.store(output, null);
        } catch (IOException e) {
            logger.fatal("Failed to load config file!");
            e.printStackTrace();
        }
    }

    public LiteralArgumentBuilder<ServerCommandSource> generateCommand(String commandName, Predicate<ServerCommandSource> requirement) {
        LiteralArgumentBuilder<ServerCommandSource> out =
                literal(commandName).requires(requirement)
                        .executes(ctx -> {
                            values.stream().filter(v -> v.command != null).forEach(value ->
                                    ctx.getSource().sendFeedback(new TranslatableText(value.command.getterText, value.value), false));
                            return 1;
                        });
        values.stream().filter(v -> v.command != null).forEach(value ->
                out.then(literal(value.name)
                        .executes(ctx -> {
                            ctx.getSource().sendFeedback(new TranslatableText(value.command.getterText, value.value), false);
                            return 1;
                        })
                        .then(argument(value.name, value.getArgumentType()).suggests(value.suggestions)
                                .executes(ctx -> {
                                    value.value = value.parseArgumentValue(ctx);
                                    ((CommandContext<ServerCommandSource>) ctx).getSource().sendFeedback(new TranslatableText(value.command.setterText, value.value), true);
                                    this.save();
                                    return 1;
                                }))));
        return out;
    }

    public Object getValue(String name) {
        return values.stream().filter(value -> value.name.equals(name)).findFirst().map(iConfigValue -> iConfigValue.value).orElse(null);
    }

    public abstract static class IConfigValue<T> {
        protected final T defaultValue;
        protected final String name;
        protected final String comment;
        protected final Command command;
        protected final SuggestionProvider<T> suggestions;
        protected T value;

        public IConfigValue(@NotNull String name, T defaultValue, @Nullable String comment, @Nullable Command command, @Nullable SuggestionProvider<T> suggestions) {
            this.name = name;
            this.defaultValue = defaultValue;
            this.comment = comment;
            this.command = command;
            this.suggestions = suggestions;
        }

        public abstract T getFromProps(Properties props);
        public void setToProps(Properties props) {
            props.setProperty(name, String.valueOf(value));
            if (comment != null) props.setProperty(name + ".comment", comment);
        }
        public abstract ArgumentType<?> getArgumentType();
        public abstract T parseArgumentValue(CommandContext<ServerCommandSource> ctx);
    }

    public static class IntegerConfigValue extends IConfigValue<Integer> {
        protected final int defaultValue;
        private final IntLimits limits;

        public IntegerConfigValue(@NotNull String name, Integer defaultValue, IntLimits limits, @Nullable String comment, @Nullable Command command, SuggestionProvider<Integer> suggestions) {
            super(name, defaultValue, comment, command, suggestions);
            this.defaultValue = defaultValue;
            this.limits = limits;
        }
        public IntegerConfigValue(@NotNull String name, Integer defaultValue, IntLimits limits, @Nullable String comment, @Nullable Command command) {
            this(name, defaultValue, limits, comment, command, null);
        }
        public IntegerConfigValue(@NotNull String name, Integer defaultValue, IntLimits limits, @Nullable Command command) {
            this(name, defaultValue, limits, null, command, null);
        }

        @Override
        public Integer getFromProps(Properties props) {
            return Integer.parseInt(props.getProperty(name));
        }

        @Override
        public ArgumentType<Integer> getArgumentType() {
            return IntegerArgumentType.integer(limits.min, limits.max);
        }
        @Override
        public Integer parseArgumentValue(CommandContext<ServerCommandSource> ctx) {
            return IntegerArgumentType.getInteger(ctx, name);
        }

        public static class IntLimits {
            int min = Integer.MIN_VALUE, max = Integer.MAX_VALUE;
            public IntLimits() {}
            public IntLimits(int min) {
                this.min = min;
            }
            public IntLimits(int min, int max) {
                this.min = min;
                this.max = max;
            }
        }
    }

    public static class BooleanConfigValue extends IConfigValue<Boolean> {
        protected final boolean defaultValue;

        public BooleanConfigValue(@NotNull String name, boolean defaultValue, @Nullable String comment, @Nullable Command command, @Nullable SuggestionProvider<Boolean> suggestions) {
            super(name, defaultValue, comment, command, suggestions);
            this.defaultValue = defaultValue;
        }
        public BooleanConfigValue(@NotNull String name, boolean defaultValue, @Nullable Command command) {
            this(name, defaultValue, null, command, null);
        }

        @Override
        public Boolean getFromProps(Properties props) {
            return Boolean.parseBoolean(props.getProperty(name));
        }

        @Override
        public ArgumentType<Boolean> getArgumentType() {
            return BoolArgumentType.bool();
        }

        @Override
        public Boolean parseArgumentValue(CommandContext<ServerCommandSource> ctx) {
            return BoolArgumentType.getBool(ctx, name);
        }
    }

    public static class Command {
        protected String setterText;
        protected String getterText;
        protected String errorText;

        public Command(String getterText, String setterText, @Nullable String errorText) {
            this.getterText = getterText;
            this.setterText = setterText;
            this.errorText = errorText;
        }
        public Command(String getterText, String setterText) {
            this(getterText, setterText, null);
        }
    }
}
