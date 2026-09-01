import { Plus, Search } from 'lucide-react'
import { useMemo, useState } from 'react'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { ScrollArea } from '@/components/ui/scroll-area'
import type { CaseSummary } from '@/lib/api-types'

interface CaseRailProps {
  cases: CaseSummary[]
  selectedId?: string
  onSelect(caseId: string): void
  onCreate(): void
}

export function CaseRail({ cases, selectedId, onSelect, onCreate }: CaseRailProps) {
  const [query, setQuery] = useState('')
  const visibleCases = useMemo(() => {
    const needle = query.trim().toLowerCase()
    if (!needle) return cases
    return cases.filter((item) => [item.title, item.businessKey, item.id]
      .some((value) => value?.toLowerCase().includes(needle)))
  }, [cases, query])

  return (
    <aside className="case-rail" aria-label="Cases">
      <div className="flex items-center justify-between gap-3">
        <div>
          <p className="eyebrow">Work queue</p>
          <h2 className="text-xl font-semibold">Cases</h2>
        </div>
        <Button size="sm" onClick={onCreate}><Plus aria-hidden="true" />New complaint</Button>
      </div>
      <div className="relative mt-5">
        <Search className="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
        <Input className="pl-9" type="search" aria-label="Search cases" value={query} onChange={(event) => setQuery(event.target.value)} />
      </div>
      {cases.length === 0 ? <p className="empty-copy">No cases yet</p> : null}
      {cases.length > 0 && visibleCases.length === 0 ? <p className="empty-copy">No cases match this search.</p> : null}
      <ScrollArea className="mt-4 h-[calc(100svh-13rem)]">
        <div className="grid gap-2 pr-3">
          {visibleCases.map((item) => (
            <button
              key={item.id}
              type="button"
              className="case-row"
              data-selected={item.id === selectedId}
              onClick={() => onSelect(item.id)}
              aria-label={`${item.title || item.businessKey || item.id}, ${item.state}`}
            >
              <span className="font-medium">{item.title || item.businessKey || 'Untitled case'}</span>
              <span className="mt-2 flex items-center justify-between gap-2">
                <span className="mono text-xs text-muted-foreground">{item.businessKey || item.id}</span>
                <Badge variant="secondary">{item.state}</Badge>
              </span>
            </button>
          ))}
        </div>
      </ScrollArea>
    </aside>
  )
}
