<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { V2Proposal } from '@/api/ai-workbench'

const props = defineProps<{ proposal: V2Proposal; current: Record<string, unknown> | null }>()
const emit = defineEmits<{ (event: 'adopt', value: Record<string, unknown>): void; (event: 'cancel'): void }>()
const selected = ref<string[]>([])
const rules = computed(() => (props.proposal.behavior.rules || []) as Array<Record<string, unknown>>)
const prior = computed(() => new Map(((props.current?.rules || []) as Array<Record<string, unknown>>).map(rule => [String(rule.id), rule])))
watch(() => props.proposal, () => { selected.value = [] })
const copy = <T,>(value: T): T => JSON.parse(JSON.stringify(value)) as T

function adopt() {
  const result = copy(props.current || props.proposal.behavior)
  const merged = new Map(((result.rules || []) as Array<Record<string, unknown>>).map(rule => [String(rule.id), rule]))
  const proposed = props.proposal.behavior
  for (const rule of rules.value.filter(item => selected.value.includes(String(item.id)))) {
    merged.set(String(rule.id), copy(rule))
    const cases = (result.verificationCases || []) as Array<Record<string, unknown>>
    const suggested = (proposed.verificationCases || []) as Array<Record<string, unknown>>
    for (const item of suggested.filter(test => ((test.steps || []) as Array<Record<string, unknown>>).some(step => step.expectedRuleId === rule.id))) {
      const index = cases.findIndex(existing => existing.id === item.id)
      if (index >= 0) cases.splice(index, 1)
      cases.push(copy(item))
    }
    result.verificationCases = cases
    const critical = new Set(((result.scope as Record<string, unknown>)?.criticalRuleIds || []) as string[])
    if (((proposed.scope as Record<string, unknown>)?.criticalRuleIds as string[] || []).includes(String(rule.id))) critical.add(String(rule.id))
    ;(result.scope as Record<string, unknown>).criticalRuleIds = [...critical]
  }
  result.rules = [...merged.values()]
  if (!props.current && selected.value.length !== rules.value.length) {
    result.rules = rules.value.filter(rule => selected.value.includes(String(rule.id))).map(rule => copy(rule))
    ;(result.scope as Record<string, unknown>).criticalRuleIds = ((result.scope as Record<string, unknown>).criticalRuleIds as string[]).filter(id => selected.value.includes(id))
    result.verificationCases = ((result.verificationCases || []) as Array<Record<string, unknown>>).filter(test =>
      ((test.steps || []) as Array<Record<string, unknown>>).every(step => selected.value.includes(String(step.expectedRuleId))))
  }
  emit('adopt', result)
}
</script>

<template>
  <div class="proposal-review">
    <strong>v2 提案 · {{ rules.length }} 条规则</strong>
    <label v-for="rule in rules" :key="String(rule.id)" class="proposal-rule">
      <input v-model="selected" type="checkbox" :value="String(rule.id)" />
      <span><b>{{ prior.has(String(rule.id)) ? '修改' : '新增' }} · {{ rule.id }}</b>
        <small>{{ JSON.stringify(rule.match) }} · {{ (rule.evidence as Record<string, unknown>)?.type }}</small>
        <small>证据：{{ JSON.stringify((rule.evidence as Record<string, unknown>)?.observationIds || []) }}</small>
        <small>原值：{{ JSON.stringify(prior.get(String(rule.id)) || null) }}</small>
        <small>提案：{{ JSON.stringify({ when: rule.when, response: rule.response, operations: rule.operations }) }}</small>
      </span>
    </label>
    <p v-for="(gap, index) in proposal.gaps" :key="index">待补证据：{{ gap }}</p>
    <div class="proposal-actions"><button type="button" :disabled="!selected.length" @click="adopt">采纳所选规则</button><button type="button" @click="emit('cancel')">取消</button></div>
  </div>
</template>

<style scoped>
.proposal-review{display:grid;gap:9px;min-width:0;border-top:1px solid #d7e1ec;padding:12px 0;overflow-wrap:anywhere}
.proposal-rule{display:flex;gap:9px;padding:9px 0;border-top:1px solid #e3e9ef;min-width:0}.proposal-rule span{display:grid;gap:4px;min-width:0}.proposal-rule small{color:#526579;word-break:break-word}.proposal-actions{display:flex;gap:8px;flex-wrap:wrap}
</style>
