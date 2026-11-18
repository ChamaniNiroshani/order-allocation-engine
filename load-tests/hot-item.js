// Every VU orders the SAME item: worst-case contention on one inventory row.
import { cfg, createItem, placeOrder, makeSummary } from './lib/common.js';

export { options } from './lib/common.js';

export function setup() {
  return { itemId: createItem(cfg.STOCK) };
}

export default function (data) {
  placeOrder(data.itemId);
}

export function handleSummary(data) {
  return makeSummary('hot-item', data);
}
