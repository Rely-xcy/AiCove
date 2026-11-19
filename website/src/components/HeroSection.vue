<template>
  <section class="hero" id="preview">
    <div class="hero__bg-grad"></div>
    <div class="hero__bg-grid bg-grid"></div>

    <div class="container">
      <div class="hero__intro reveal" v-reveal>
        <div class="hero__title-row">
          <img src="/assets/logo.png" alt="AiCove" class="hero__app-logo" />
          <h1 class="hero__title">AiCove</h1>
        </div>

        <p class="hero__subtitle">
          {{ copy.site.hero.subtitle }}
        </p>

        <div class="hero__actions">
          <a
            href="https://github.com/Rely-xcy/AiCove/releases/latest"
            target="_blank"
            rel="noopener noreferrer"
            class="btn btn--primary btn--lg"
          >
            <i class="ri-download-2-line"></i>
            <span>{{ copy.site.hero.download }}</span>
          </a>
          <a
            href="https://rely-xcy.github.io/AiCove/"
            target="_blank"
            rel="noopener noreferrer"
            class="btn btn--outline btn--lg btn--arrow"
          >
            <span>{{ copy.site.hero.docs }}</span>
            <i class="ri-arrow-right-line"></i>
          </a>
        </div>
      </div>

      <AppReplica />
    </div>
  </section>
</template>

<script setup>
import { useLocale } from "../composables/useLocale.js";
import AppReplica from "./AppReplica.vue";

const { copy } = useLocale();
</script>
