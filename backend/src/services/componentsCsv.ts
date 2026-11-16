import { escapeCsvField } from '../utils/csv.js';
import { component } from '../drizzle/schema.js';
import type { InferSelectModel } from 'drizzle-orm';

export type ComponentRecord = InferSelectModel<typeof component>;

export function componentToCsvRow(c: ComponentRecord): string {
  return [
    escapeCsvField(c.id),
    escapeCsvField(c.name),
    escapeCsvField(c.description),
    escapeCsvField(c.category?.replace(/_/g, ' ') ?? null),
    escapeCsvField(c.location?.replace(/_/g, ' ') ?? null),
    escapeCsvField(c.imageUrl),
    escapeCsvField(c.createdAt),
    escapeCsvField(c.updatedAt),
    c.totalQuantity,
    c.availableQuantity,
  ].join(',');
}

export function generateComponentsCsv(components: ComponentRecord[]): string {
  const header =
    'ID,Name,Description,Category,Location,Image URL,Created At,Updated At,Total Quantity,Available Quantity';
  const rows = components.map(componentToCsvRow);
  return [header, ...rows].join('\n');
}
