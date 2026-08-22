import { ref } from 'vue'
import { useToast } from './useToast'

const copied = ref(null)

export function useCopy() {
  const toast = useToast()

  const copy = async (text, description = '') => {
    try {
      await navigator.clipboard.writeText(text)
      copied.value = description || text
      toast.success(`已复制${description ? ': ' + description : ''}`)
      setTimeout(() => { copied.value = null }, 2000)
      return true
    } catch (err) {
      const textarea = document.createElement('textarea')
      textarea.value = text
      textarea.style.position = 'fixed'
      textarea.style.top = '-9999px'
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
      copied.value = description || text
      toast.success(`已复制${description ? ': ' + description : ''}`)
      setTimeout(() => { copied.value = null }, 2000)
      return true
    }
  }

  return {
    copy,
    copied
  }
}
