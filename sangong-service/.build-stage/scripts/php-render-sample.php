<?php
/**
 * 独立渲染 PHP 版报表样图（桩掉 Laravel 依赖），用于与 Java 版逐像素对比。
 * 用法：php php-render-sample.php <输出目录>
 */

namespace {
    error_reporting(E_ALL & ~E_DEPRECATED & ~E_WARNING & ~E_NOTICE);

    define('SANGONG_API', '/www/wwwroot/99chat-server/sangong/api');
    define('SAMPLE_OUT', $argv[1] ?? '/tmp/sangong-img-compare/php');
    @mkdir(SAMPLE_OUT, 0777, true);

    function storage_path(string $path = ''): string
    {
        // 输出目录（app/...）转到可写临时目录；字体等仍指向原项目 storage
        if (strpos($path, 'app/') === 0 || $path === 'app') {
            return sys_get_temp_dir() . '/sangong-php-storage/' . $path;
        }
        return SANGONG_API . '/storage' . ($path !== '' ? '/' . $path : '');
    }

    function config(string $key, $default = null)
    {
        $map = [
            'sangong.image.scale' => 3,
            'sangong.image.jpeg_quality' => 85,
            'sangong.bet_report.title' => '三公',
            'sangong.image.avatar_fetch_concurrency' => 8,
            'sangong.image.avatar_fetch_timeout' => 3,
            'sangong.image.avatar_cache_ttl' => 86400,
        ];
        return $map[$key] ?? $default;
    }
}

namespace Illuminate\Support\Facades {
    class Log
    {
        public static function __callStatic($name, $args) {}
    }
}

namespace App\Services {
    // 桩：不访问外网头像
    class ImService
    {
        public function getPortraitFaceUrls(array $imUserIds): array
        {
            return [];
        }
    }

    class ReportImageCacheService
    {
        public function remember(string $type, array $payload, callable $fn)
        {
            return $fn();
        }
    }
}

namespace {
    require SANGONG_API . '/app/Services/ImageScaleHelper.php';
    require SANGONG_API . '/app/Services/ImageBorderHelper.php';
    require SANGONG_API . '/app/Services/ImageScoreColorHelper.php';
    require SANGONG_API . '/app/Services/ImageFontHelper.php';
    require SANGONG_API . '/app/Services/ImageTitleHelper.php';
    require SANGONG_API . '/app/Services/ImageTextHelper.php';
    require SANGONG_API . '/app/Services/ImagePlayerCellHelper.php';
    require SANGONG_API . '/app/Services/ImageEncodeHelper.php';
    require SANGONG_API . '/app/Services/ImageMemoryHelper.php';
    require SANGONG_API . '/app/Services/AvatarCacheService.php';
    require SANGONG_API . '/app/Services/UserPointsImageService.php';
    require SANGONG_API . '/app/Services/BetReportImageService.php';
    require SANGONG_API . '/app/Services/SettleReportImageService.php';
    require SANGONG_API . '/app/Services/TrendChartImageService.php';
    require SANGONG_API . '/app/Services/AdminSettleBillImageService.php';

    $im = new App\Services\ImService();
    $avatars = new App\Services\AvatarCacheService();
    $cache = new App\Services\ReportImageCacheService();
    $out = SAMPLE_OUT;

    $sample = json_decode(file_get_contents(__DIR__ . '/sample-report-data.json'), true);

    function saveAs(?string $path, string $out, string $name): void
    {
        if ($path === null) {
            fwrite(STDERR, "render failed: $name\n");
            return;
        }
        copy($path, $out . '/' . $name);
        unlink($path);
        echo "$name ok\n";
    }

    $points = new App\Services\UserPointsImageService($im, $avatars, $cache);
    saveAs($points->generate($sample['points']), $out, 'points.jpg');

    $bet = new App\Services\BetReportImageService($im, $avatars, $cache);
    saveAs($bet->generate($sample['bet']), $out, 'bet.jpg');

    $settle = new App\Services\SettleReportImageService($im, $avatars, $cache);
    saveAs($settle->generate($sample['settle']), $out, 'settle.jpg');

    $trend = new App\Services\TrendChartImageService($cache);
    saveAs($trend->generate($sample['trend']), $out, 'trend.jpg');

    $bill = new App\Services\AdminSettleBillImageService($im, $avatars, $cache);
    saveAs($bill->generate($sample['bill']), $out, 'bill.jpg');
}
