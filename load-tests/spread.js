// Requests spread over many items: low contention per row.
import { cfg, createItem, placeOrder, makeSummary } from './lib/common.js';

export { options } from './lib/common.js';

export function setup() {
  const itemIds = [];
  for (let i = 0; i < cfg.ITEMS; i++) {
    itemIds.push(createItem(cfg.STOCK));
  }
  return { itemIds };
}

export default function (data) {
  placeOrder(data.itemIds[Math.floor(Math.random() * data.itemIds.length)]);
}

export function handleSummary(data) {
  return makeSummary('spread', data);
}
