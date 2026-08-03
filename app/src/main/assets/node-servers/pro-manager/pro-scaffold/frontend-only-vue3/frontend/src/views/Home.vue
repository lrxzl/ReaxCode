<template>
  <div class="container">
    <h1>{{PROJECT_TITLE}}</h1>
    <p class="subtitle">项目已成功创建！</p>

    <div class="card">
      <h2>开始开发</h2>

      现在你可以在智能体的消息输入框中输入需求

    </div>
  </div>
</template>

<script setup>
</script>

<style scoped>
.subtitle {
  color: var(--text-secondary);
  margin-bottom: 24px;
}
code {
  background: var(--bg-input);
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 13px;
}
</style>
