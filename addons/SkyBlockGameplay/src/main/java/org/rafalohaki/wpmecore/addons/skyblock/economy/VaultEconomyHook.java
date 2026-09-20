package org.rafalohaki.wpmecore.addons.skyblock.economy;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.logging.Level;

/** Reflective Vault registration without joining Vault's Bukkit classloader group. */
public final class VaultEconomyHook {

    private final JavaPlugin plugin;
    private Object proxy;
    private Class<Object> economyInterface;

    public VaultEconomyHook(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean register(@NotNull SkyBlockVaultProvider provider) {
        Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
        if (vault == null || !vault.isEnabled()) {
            plugin.getLogger().severe("Vault is required for the SkyBlock economy provider.");
            return false;
        }
        try {
            ClassLoader loader = vault.getClass().getClassLoader();
            Class<?> economy = loader.loadClass("net.milkbowl.vault.economy.Economy");
            Class<?> response = loader.loadClass("net.milkbowl.vault.economy.EconomyResponse");
            Class<?> responseType = loader.loadClass(
                    "net.milkbowl.vault.economy.EconomyResponse$ResponseType");
            Constructor<?> responseConstructor = response.getConstructor(
                    double.class, double.class, responseType, String.class);
            InvocationHandler handler = new Handler(provider, responseConstructor,
                    responseType.getField("SUCCESS").get(null),
                    responseType.getField("FAILURE").get(null),
                    responseType.getField("NOT_IMPLEMENTED").get(null));
            this.proxy = Proxy.newProxyInstance(loader, new Class[]{economy}, handler);
            @SuppressWarnings("unchecked")
            Class<Object> typed = (Class<Object>) economy;
            this.economyInterface = typed;
            Bukkit.getServicesManager().register(typed, proxy, plugin, ServicePriority.Highest);
            return true;
        } catch (Throwable failure) {
            plugin.getLogger().log(Level.SEVERE, "Failed to register Vault economy provider", failure);
            return false;
        }
    }

    public void unregister() {
        if (proxy != null && economyInterface != null) {
            Bukkit.getServicesManager().unregister(economyInterface, proxy);
        }
        proxy = null;
        economyInterface = null;
    }

    private static final class Handler implements InvocationHandler {

        private final SkyBlockVaultProvider provider;
        private final Constructor<?> responseConstructor;
        private final Object success;
        private final Object failure;
        private final Object notImplemented;

        private Handler(SkyBlockVaultProvider provider, Constructor<?> responseConstructor,
                        Object success, Object failure, Object notImplemented) {
            this.provider = provider;
            this.responseConstructor = responseConstructor;
            this.success = success;
            this.failure = failure;
            this.notImplemented = notImplemented;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            Object[] args = arguments == null ? new Object[0] : arguments;
            String name = method.getName();
            return switch (name) {
                case "isEnabled" -> provider.isEnabled();
                case "getName" -> provider.getName();
                case "hasBankSupport" -> provider.hasBankSupport();
                case "fractionalDigits" -> provider.fractionalDigits();
                case "currencyNamePlural" -> provider.currencyNamePlural();
                case "currencyNameSingular" -> provider.currencyNameSingular();
                case "format" -> provider.format((double) args[0]);
                case "hasAccount" -> hasAccount(args);
                case "createPlayerAccount" -> createPlayerAccount(args);
                case "getBalance" -> getBalance(args);
                case "has" -> has(args);
                case "withdrawPlayer" -> response(withdraw(args));
                case "depositPlayer" -> response(deposit(args));
                case "createBank" -> response(args[1] instanceof OfflinePlayer player
                        ? provider.createBank((String) args[0], player)
                        : provider.createBank((String) args[0], (String) args[1]));
                case "deleteBank" -> response(provider.deleteBank((String) args[0]));
                case "bankBalance" -> response(provider.bankBalance((String) args[0]));
                case "bankHas" -> response(provider.bankHas((String) args[0], (double) args[1]));
                case "bankWithdraw" -> response(provider.bankWithdraw((String) args[0], (double) args[1]));
                case "bankDeposit" -> response(provider.bankDeposit((String) args[0], (double) args[1]));
                case "isBankOwner" -> response(args[1] instanceof OfflinePlayer player
                        ? provider.isBankOwner((String) args[0], player)
                        : provider.isBankOwner((String) args[0], (String) args[1]));
                case "isBankMember" -> response(args[1] instanceof OfflinePlayer player
                        ? provider.isBankMember((String) args[0], player)
                        : provider.isBankMember((String) args[0], (String) args[1]));
                case "getBanks" -> provider.getBanks();
                case "toString" -> "VaultEconomyProxy(WpmeSkyBlock)";
                case "hashCode" -> System.identityHashCode(provider);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException("Unknown Vault method " + name);
            };
        }

        private boolean hasAccount(Object[] args) {
            if (args.length == 1) {
                return args[0] instanceof OfflinePlayer player
                        ? provider.hasAccount(player) : provider.hasAccount((String) args[0]);
            }
            return args[0] instanceof OfflinePlayer player
                    ? provider.hasAccount(player, (String) args[1])
                    : provider.hasAccount((String) args[0], (String) args[1]);
        }

        private boolean createPlayerAccount(Object[] args) {
            if (args.length == 1) {
                return args[0] instanceof OfflinePlayer player
                        ? provider.createPlayerAccount(player)
                        : provider.createPlayerAccount((String) args[0]);
            }
            return args[0] instanceof OfflinePlayer player
                    ? provider.createPlayerAccount(player, (String) args[1])
                    : provider.createPlayerAccount((String) args[0], (String) args[1]);
        }

        private double getBalance(Object[] args) {
            if (args.length == 1) {
                return args[0] instanceof OfflinePlayer player
                        ? provider.getBalance(player) : provider.getBalance((String) args[0]);
            }
            return args[0] instanceof OfflinePlayer player
                    ? provider.getBalance(player, (String) args[1])
                    : provider.getBalance((String) args[0], (String) args[1]);
        }

        private boolean has(Object[] args) {
            if (args.length == 2) {
                return args[0] instanceof OfflinePlayer player
                        ? provider.has(player, (double) args[1])
                        : provider.has((String) args[0], (double) args[1]);
            }
            return args[0] instanceof OfflinePlayer player
                    ? provider.has(player, (String) args[1], (double) args[2])
                    : provider.has((String) args[0], (String) args[1], (double) args[2]);
        }

        private SkyBlockVaultProvider.Response withdraw(Object[] args) {
            if (args.length == 2) {
                return args[0] instanceof OfflinePlayer player
                        ? provider.withdrawPlayer(player, (double) args[1])
                        : provider.withdrawPlayer((String) args[0], (double) args[1]);
            }
            return args[0] instanceof OfflinePlayer player
                    ? provider.withdrawPlayer(player, (String) args[1], (double) args[2])
                    : provider.withdrawPlayer((String) args[0], (String) args[1], (double) args[2]);
        }

        private SkyBlockVaultProvider.Response deposit(Object[] args) {
            if (args.length == 2) {
                return args[0] instanceof OfflinePlayer player
                        ? provider.depositPlayer(player, (double) args[1])
                        : provider.depositPlayer((String) args[0], (double) args[1]);
            }
            return args[0] instanceof OfflinePlayer player
                    ? provider.depositPlayer(player, (String) args[1], (double) args[2])
                    : provider.depositPlayer((String) args[0], (String) args[1], (double) args[2]);
        }

        private Object response(SkyBlockVaultProvider.Response value) throws ReflectiveOperationException {
            Object type = switch (value.type()) {
                case SUCCESS -> success;
                case FAILURE -> failure;
                case NOT_IMPLEMENTED -> notImplemented;
            };
            return responseConstructor.newInstance(
                    value.amount(), value.balance(), type, value.errorMessage());
        }
    }
}
