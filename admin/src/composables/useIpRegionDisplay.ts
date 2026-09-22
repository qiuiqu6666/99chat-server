import { ref } from "vue";
import {
  displayIpRegion,
  normalizeIp,
  resolveIpRegions
} from "@/utils/ipRegion";

/** 列表页 IP 地区展示：加载数据后调用 lookup，模板里用 regionText */
export function useIpRegionDisplay() {
  const regionByIp = ref<Record<string, string>>({});

  async function lookupFromRows(
    ips: Array<string | null | undefined>
  ): Promise<void> {
    const mapped = await resolveIpRegions(ips);
    regionByIp.value = { ...regionByIp.value, ...mapped };
  }

  function regionText(
    ip: unknown,
    backendRegion?: unknown
  ): string {
    return displayIpRegion(ip, regionByIp.value, backendRegion);
  }

  function pickIp(row: Record<string, unknown>, ...keys: string[]): string | null {
    for (const key of keys) {
      const v = normalizeIp(row[key]);
      if (v) return v;
    }
    return null;
  }

  return {
    regionByIp,
    lookupFromRows,
    regionText,
    pickIp
  };
}
