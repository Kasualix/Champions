package top.theillusivec4.champions.common;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.LootContext;
import net.minecraft.loot.LootParameterSets;
import net.minecraft.loot.LootParameters;
import net.minecraft.loot.LootTable;
import net.minecraft.potion.Effect;
import net.minecraft.potion.EffectInstance;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Tuple;
import net.minecraft.util.Util;
import net.minecraft.util.text.ChatType;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.Explosion;
import net.minecraft.world.GameRules;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import top.theillusivec4.champions.Champions;
import top.theillusivec4.champions.api.IChampion;
import top.theillusivec4.champions.common.capability.ChampionCapability;
import top.theillusivec4.champions.common.config.ChampionsConfig;
import top.theillusivec4.champions.common.config.ConfigEnums.LootSource;
import top.theillusivec4.champions.common.config.ConfigLoot;
import top.theillusivec4.champions.common.rank.Rank;
import top.theillusivec4.champions.common.registry.ChampionsRegistry;
import top.theillusivec4.champions.common.registry.RegistryReference;
import top.theillusivec4.champions.common.util.ChampionBuilder;

public class ChampionEventsHandler {

  private static final Field EXPLOSION_SIZE = ObfuscationReflectionHelper
      .findField(Explosion.class, "field_77280_f");

  @SubscribeEvent
  public void onLivingDrops(LivingDropsEvent evt) {
    LivingEntity livingEntity = evt.getEntityLiving();

    if (!livingEntity.getEntityWorld().getGameRules().getBoolean(GameRules.DO_MOB_LOOT) || (
        !ChampionsConfig.fakeLoot && evt.getSource().getTrueSource() instanceof FakePlayer)) {
      return;
    }
    ChampionCapability.getCapability(livingEntity).ifPresent(champion -> {
      IChampion.Server serverChampion = champion.getServer();
      ServerWorld serverWorld = (ServerWorld) livingEntity.getEntityWorld();

      if (ChampionsConfig.lootSource != LootSource.CONFIG) {
        LootTable lootTable = serverWorld.getServer().getLootTableManager()
            .getLootTableFromLocation(new ResourceLocation(RegistryReference.CHAMPION_LOOT));
        DamageSource source = evt.getSource();
        LootContext.Builder lootcontext$builder = (new LootContext.Builder(serverWorld)
            .withRandom(livingEntity.getRNG())
            .withParameter(LootParameters.THIS_ENTITY, livingEntity)
            .withParameter(LootParameters.field_237457_g_, livingEntity.getPositionVec())
            .withParameter(LootParameters.DAMAGE_SOURCE, source)
            .withNullableParameter(LootParameters.KILLER_ENTITY, source.getTrueSource())
            .withNullableParameter(LootParameters.DIRECT_KILLER_ENTITY,
                source.getImmediateSource()));
        LivingEntity attackingEntity = livingEntity.getAttackingEntity();

        if (attackingEntity instanceof PlayerEntity) {
          lootcontext$builder = lootcontext$builder
              .withParameter(LootParameters.LAST_DAMAGE_PLAYER, (PlayerEntity) attackingEntity)
              .withLuck(((PlayerEntity) attackingEntity).getLuck());
        }
        List<ItemStack> stacks = lootTable
            .generate(lootcontext$builder.build(LootParameterSets.ENTITY));
        stacks.forEach(stack -> {
          ItemEntity itemEntity = new ItemEntity(serverWorld, livingEntity.getPosX(),
              livingEntity.getPosY(), livingEntity.getPosZ(), stack);
          itemEntity.setDefaultPickupDelay();
          evt.getDrops().add(itemEntity);
        });
      }

      if (ChampionsConfig.lootSource != LootSource.LOOT_TABLE) {
        List<ItemStack> loot = ConfigLoot
            .getLootDrops(serverChampion.getRank().map(Rank::getTier).orElse(0));

        if (!loot.isEmpty()) {
          loot.forEach(stack -> {
            ItemEntity itemEntity = new ItemEntity(serverWorld, livingEntity.getPosX(),
                livingEntity.getPosY(), livingEntity.getPosZ(), stack);
            itemEntity.setDefaultPickupDelay();
            evt.getDrops().add(itemEntity);
          });
        }
      }
    });
  }

  @SubscribeEvent
  public void onLivingXpDrop(LivingExperienceDropEvent evt) {
    LivingEntity livingEntity = evt.getEntityLiving();
    ChampionCapability.getCapability(livingEntity)
        .ifPresent(champion -> champion.getServer().getRank().ifPresent(rank -> {
          int growth = rank.getGrowthFactor();

          if (growth > 0) {
            evt.setDroppedExperience(
                growth * ChampionsConfig.experienceGrowth * evt.getOriginalExperience() +
                    evt.getDroppedExperience());
          }
        }));
  }

  @SubscribeEvent
  public void onExplosion(ExplosionEvent.Start evt) {
    Explosion explosion = evt.getExplosion();
    LivingEntity livingEntity = explosion.getExplosivePlacedBy();

    if (livingEntity != null && !livingEntity.getEntityWorld().isRemote()) {
      ChampionCapability.getCapability(livingEntity)
          .ifPresent(champion -> champion.getServer().getRank().ifPresent(rank -> {
            int growth = rank.getGrowthFactor();

            if (growth > 0) {
              try {
                float size = EXPLOSION_SIZE.getFloat(explosion);
                EXPLOSION_SIZE.setFloat(explosion, size + ChampionsConfig.explosionGrowth * growth);
              } catch (IllegalAccessException e) {
                Champions.LOGGER.error("Cannot increase explosion size!");
              }
            }
          }));
    }
  }

  @SubscribeEvent
  public void onLivingJoinWorld(EntityJoinWorldEvent evt) {
    Entity entity = evt.getEntity();

    if (!entity.getEntityWorld().isRemote()) {
      ChampionCapability.getCapability(entity).ifPresent(champion -> {
        IChampion.Server serverChampion = champion.getServer();
        Optional<Rank> maybeRank = serverChampion.getRank();

        if (!maybeRank.isPresent()) {
          ChampionBuilder.spawn(champion);
        }
        serverChampion.getAffixes().forEach(affix -> affix.onSpawn(champion));
        serverChampion.getRank().ifPresent(rank -> {
          List<Tuple<Effect, Integer>> effects = rank.getEffects();
          effects.forEach(effectPair -> champion.getLivingEntity()
              .addPotionEffect(new EffectInstance(effectPair.getA(), 200, effectPair.getB())));
        });
      });
    }
  }

  @SubscribeEvent
  public void onLivingUpdate(LivingUpdateEvent evt) {
    LivingEntity livingEntity = evt.getEntityLiving();

    if (!livingEntity.getEntityWorld().isRemote()) {
      ChampionCapability.getCapability(livingEntity).ifPresent(champion -> {
        IChampion.Server serverChampion = champion.getServer();
        serverChampion.getAffixes().forEach(affix -> affix.onUpdate(champion));
        serverChampion.getRank().ifPresent(rank -> {
          if (ChampionsConfig.showParticles && rank.getTier() > 0) {
            int color = rank.getDefaultColor();
            float r = (float) ((color >> 16) & 0xFF) / 255f;
            float g = (float) ((color >> 8) & 0xFF) / 255f;
            float b = (float) ((color) & 0xFF) / 255f;
            ((ServerWorld) livingEntity.getEntityWorld()).spawnParticle(ChampionsRegistry.RANK,
                livingEntity.getPosX()
                    + (livingEntity.getRNG().nextDouble() - 0.5D) * (double) livingEntity
                    .getWidth(),
                livingEntity.getPosY() + livingEntity.getRNG().nextDouble() * livingEntity
                    .getHeight(), livingEntity.getPosZ()
                    + (livingEntity.getRNG().nextDouble() - 0.5D) * (double) livingEntity
                    .getWidth(), 0, r, g, b, 1);
          }

          if (livingEntity.ticksExisted % 40 == 0) {
            List<Tuple<Effect, Integer>> effects = rank.getEffects();
            effects.forEach(effectPair -> livingEntity
                .addPotionEffect(new EffectInstance(effectPair.getA(), 100, effectPair.getB())));
          }
        });
      });
    }
  }

  @SubscribeEvent
  public void onLivingAttack(LivingAttackEvent evt) {
    LivingEntity livingEntity = evt.getEntityLiving();

    if (livingEntity.getEntityWorld().isRemote()) {
      return;
    }
    ChampionCapability.getCapability(livingEntity).ifPresent(champion -> {
      IChampion.Server serverChampion = champion.getServer();
      serverChampion.getAffixes().forEach(affix -> {

        if (!affix.onAttacked(champion, evt.getSource(), evt.getAmount())) {
          evt.setCanceled(true);
        }
      });
    });

    if (evt.isCanceled()) {
      return;
    }
    Entity source = evt.getSource().getTrueSource();

    if (source instanceof LivingEntity) {
      ChampionCapability.getCapability(source).ifPresent(champion -> {
        IChampion.Server serverChampion = champion.getServer();
        serverChampion.getAffixes().forEach(affix -> {

          if (!affix.onAttack(champion, evt.getEntityLiving(), evt.getSource(), evt.getAmount())) {
            evt.setCanceled(true);
          }
        });
      });
    }
  }

  @SubscribeEvent
  public void onLivingHurt(LivingHurtEvent evt) {
    LivingEntity livingEntity = evt.getEntityLiving();

    if (!livingEntity.getEntityWorld().isRemote()) {
      float[] amounts = new float[] {evt.getAmount(), evt.getAmount()};
      ChampionCapability.getCapability(livingEntity).ifPresent(champion -> {
        IChampion.Server serverChampion = champion.getServer();
        serverChampion.getAffixes().forEach(
            affix -> amounts[1] = affix.onHurt(champion, evt.getSource(), amounts[0], amounts[1]));
      });
      evt.setAmount(amounts[1]);
    }
  }

  @SubscribeEvent
  public void onLivingDamage(LivingDamageEvent evt) {
    LivingEntity livingEntity = evt.getEntityLiving();

    if (!livingEntity.getEntityWorld().isRemote()) {
      float[] amounts = new float[] {evt.getAmount(), evt.getAmount()};
      ChampionCapability.getCapability(livingEntity).ifPresent(champion -> {
        IChampion.Server serverChampion = champion.getServer();
        serverChampion.getAffixes().forEach(affix -> amounts[1] = affix
            .onDamage(champion, evt.getSource(), amounts[0], amounts[1]));
      });
      evt.setAmount(amounts[1]);
    }
  }

  @SubscribeEvent
  public void onLivingDeath(LivingDeathEvent evt) {
    LivingEntity livingEntity = evt.getEntityLiving();

    if (livingEntity.getEntityWorld().isRemote()) {
      return;
    }
    ChampionCapability.getCapability(livingEntity).ifPresent(champion -> {
      IChampion.Server serverChampion = champion.getServer();
      serverChampion.getAffixes().forEach(affix -> {

        if (!affix.onDeath(champion, evt.getSource())) {
          evt.setCanceled(true);
        }
      });
      serverChampion.getRank().ifPresent(rank -> {
        if (!evt.isCanceled() && evt.getSource().getTrueSource() instanceof PlayerEntity) {
          int messageTier = ChampionsConfig.deathMessageTier;

          if (messageTier > 0 && rank.getTier() >= messageTier) {
            MinecraftServer server = livingEntity.getServer();

            if (server != null) {
              server.getPlayerList().func_232641_a_(
                  new TranslationTextComponent("rank.champions.title." + rank.getTier())
                      .appendString(" ")
                      .append(livingEntity.getCombatTracker().getDeathMessage()),
                  ChatType.SYSTEM, Util.DUMMY_UUID);
            }
          }
        }
      });
    });
  }

  @SubscribeEvent
  public void onLivingHeal(LivingHealEvent evt) {
    LivingEntity livingEntity = evt.getEntityLiving();

    if (!livingEntity.getEntityWorld().isRemote()) {
      float[] amounts = new float[] {evt.getAmount(), evt.getAmount()};
      ChampionCapability.getCapability(livingEntity).ifPresent(champion -> {
        IChampion.Server serverChampion = champion.getServer();
        serverChampion.getAffixes()
            .forEach(affix -> amounts[1] = affix.onHeal(champion, amounts[0], amounts[1]));
      });
      evt.setAmount(amounts[1]);
    }
  }
}
