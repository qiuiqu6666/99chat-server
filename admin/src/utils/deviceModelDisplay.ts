import appleModels from "@/data/apple-ios-models.json";

const APPLE_MODEL_MAP: Readonly<Record<string, string>> = Object.freeze(
  Object.fromEntries(
    Object.entries(appleModels as Record<string, string>).map(([k, v]) => [
      k.trim(),
      String(v).trim()
    ])
  )
);

const GENERIC_PLATFORM = new Set(["ios", "android", "web"]);

const APPLE_HARDWARE_ID =
  /^(iPhone|iPad|iPod|Watch|AppleTV|RealityDevice|AudioAccessory)\d+,\d+$/i;

/** Admin `device_type` → platform（与后端 / App 一致） */
export function deviceTypeToPlatform(deviceType: unknown): string | null {
  const v = Number(deviceType);
  if (!Number.isFinite(v)) return null;
  if (v === 1) return "ios";
  if (v === 0) return "android";
  if (v === 2) return "web";
  return null;
}

function platformFallback(platform: string | null | undefined): string | null {
  if (platform == null || platform === "") return null;
  const p = platform.trim().toLowerCase();
  if (p === "ios") return "iPhone";
  if (p === "android") return "Android";
  if (p === "web") return "Web";
  return platform.trim();
}

function lookupApple(identifier: string): string | null {
  return (
    APPLE_MODEL_MAP[identifier] ??
    APPLE_MODEL_MAP[identifier.toLowerCase()] ??
    null
  );
}

function genericAppleFamily(identifier: string): string {
  const lower = identifier.toLowerCase();
  if (lower.startsWith("iphone")) return "iPhone";
  if (lower.startsWith("ipad")) return "iPad";
  if (lower.startsWith("ipod")) return "iPod touch";
  if (lower.startsWith("watch")) return "Apple Watch";
  if (lower.startsWith("appletv")) return "Apple TV";
  return identifier;
}

/** 将 Apple 硬件标识（如 iPhone17,1）转为可读型号；与服务端 DeviceModelDisplayService 对齐。 */
export function displayDeviceModel(
  platform: string | null | undefined,
  rawModel: string | null | undefined
): string | null {
  if (rawModel == null || rawModel === "") {
    return platformFallback(platform);
  }
  const trimmed = String(rawModel).trim();
  if (!trimmed) {
    return platformFallback(platform);
  }
  if (GENERIC_PLATFORM.has(trimmed.toLowerCase())) {
    return platformFallback(platform ?? trimmed);
  }
  const mapped = lookupApple(trimmed);
  if (mapped) return mapped;
  if (APPLE_HARDWARE_ID.test(trimmed)) {
    return genericAppleFamily(trimmed);
  }
  return trimmed;
}

/** 表格空值展示 */
export function displayDeviceModelCell(
  platform: string | null | undefined,
  rawModel: string | null | undefined
): string {
  return displayDeviceModel(platform, rawModel) ?? "—";
}
