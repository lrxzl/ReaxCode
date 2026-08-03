<template>
  <div class="container">
    <!--  TODO 开始开发前，请将container里面的所有内容都清空，包括本条注释  -->
    <h1>{{PROJECT_TITLE}}</h1>
    <p class="subtitle">项目已成功创建！</p>

    <div class="card">
      <h2>测试实例</h2>
      <div class="form">
        <input v-model="newItem.name" placeholder="名称" />
        <input v-model="newItem.description" placeholder="描述" />
        <button class="btn btn-primary" @click="createItem">添加</button>
      </div>

      <div class="list">
        <div v-for="item in items" :key="item.id" class="list-item">
          <div>
            <strong>{{ item.name }}</strong>
            <span v-if="item.description"> - {{ item.description }}</span>
          </div>
          <button class="btn btn-danger" @click="deleteItem(item.id)">删除</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import axios from 'axios'

const items = ref([])
const newItem = ref({ name: '', description: '' })

const fetchItems = async () => {
  const { data } = await axios.get('/api/items')
  if (data.success) items.value = data.data
}

const createItem = async () => {
  if (!newItem.value.name) return
  await axios.post('/api/items', newItem.value)
  newItem.value = { name: '', description: '' }
  fetchItems()
}

const deleteItem = async (id) => {
  await axios.delete(`/api/items/${id}`)
  fetchItems()
}

onMounted(fetchItems)
</script>

<style scoped>
.subtitle {
  color: var(--text-secondary);
  margin-bottom: 24px;
}
.form {
  display: flex;
  gap: 10px;
  margin-bottom: 20px;
}
.form input {
  flex: 1;
}
.list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.list-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px;
  background: var(--bg);
  border-radius: 8px;
}
.btn-danger {
  background: var(--danger);
  color: white;
}
</style>
