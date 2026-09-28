import appleModels from '@/data/apple-ios-models.json';

const APPLE_MODEL_MAP = new Map<string, string>(
  Object.entries(appleModels).map(([key, name]) => [normalizeAppleId(key), name.trim()])
);

const APPLE_HARDWARE_ID = /^(iphone|ipad|ipod|watch|appletv|realitydevice|audioaccessory)\d+,\d+$/;

function normalizeAppleId(value: string) {
  return value.trim().replace(/[，]/g, ',').replace(/\s+/g, '').toLowerCase();
}

function platformFallback(platform: string | null) {
  if (!platform) return null;
  const key = platform.trim().toLowerCase();
  if (key === 'ios' || key === '1') return '苹果';
  if (key === 'android' || key === '0') return '安卓';
  if (key === 'web' || key === '2' || key === 'desktop' || key === 'pc') return '电脑';
  return platform.trim();
}

function genericAppleFamily(identifier: string) {
  if (identifier.startsWith('iphone')) return 'iPhone';
  if (identifier.startsWith('ipad')) return 'iPad';
  if (identifier.startsWith('ipod')) return 'iPod touch';
  if (identifier.startsWith('watch')) return 'Apple Watch';
  if (identifier.startsWith('appletv')) return 'Apple TV';
  return null;
}

/** 把 iPhone13,4、IPHONE 13，4 这类硬件号转成 iPhone 12 Pro Max。 */
export function displayDeviceModel(platform: string | null, rawModel: unknown) {
  const raw = String(rawModel ?? '').trim();
  if (!raw || ['ios', 'android', 'web'].includes(raw.toLowerCase())) {
    return platformFallback(platform) ?? '—';
  }
  const key = normalizeAppleId(raw);
  const mapped = APPLE_MODEL_MAP.get(key);
  if (mapped) return mapped;
  if (APPLE_HARDWARE_ID.test(key)) return genericAppleFamily(key) ?? raw;
  return raw;
}
