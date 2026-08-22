
<template>
  <div id="app">
    <router-view />
    <div class="toast-container">
      <div
        v-for="toast in toasts"
        :key="toast.id"
        :class="['toast', `toast-${toast.type}`]"
      >
        <span class="toast-text">{{ toast.message }}</span>
        <button v-if="toast.type !== 'success'" class="toast-copy" @click="copy(toast.message)" title="复制">📋</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { useToast } from './composables/useToast'
import { useCopy } from './composables/useCopy'

const { toasts } = useToast()
const { copy } = useCopy()
</script>
