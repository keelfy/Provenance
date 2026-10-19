import { Button } from "@/components/ui/button"
import { formatSpan, stackLabel } from "@/lib/format"
import type { Group } from "@/lib/graph"

/** The entries behind a plate: where each one is, when it happened and how much it gave away. */
export function GroupMembers({ group, onPick }: { group: Group; onPick: (member: string) => void }) {
  return (
    <section className="border-b border-border p-4">
      <h3 className="font-semibold">Where, when and how many</h3>
      <p className="mt-1 text-sm text-muted-foreground">Pick an entry to see it on its own.</p>
      <ul className="mt-2 space-y-1">
        {group.members.map((member) => (
          <li key={member.id}>
            <Button
              variant="outline"
              className="h-auto w-full flex-col items-stretch gap-0.5 py-2 text-left font-normal whitespace-normal"
              onClick={() => onPick(member.id)}
            >
              <span className="flex items-baseline justify-between gap-3">
                <span className="font-semibold">{member.where}</span>
                <span className="shrink-0 text-sm">{stackLabel(member.items)}</span>
              </span>
              <span className="text-xs text-muted-foreground">
                {member.killedBy ? `Killed by ${member.killedBy} · ` : ""}
                {formatSpan(member.from, member.until)} · {member.events} {member.events === 1 ? "event" : "events"}
              </span>
            </Button>
          </li>
        ))}
      </ul>
    </section>
  )
}
