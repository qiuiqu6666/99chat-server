import type { CustomRoute, ElegantConstRoute, ElegantRoute } from '@elegant-router/types';
import { generatedRoutes } from '../elegant/routes';
import { layouts, views } from '../elegant/imports';
import { transformElegantRoutesToVueRoutes } from '../elegant/transform';
import { businessRoutes } from '../business-routes';

const builtinNames = new Set(['403', '404', '500', 'login', 'iframe-page']);

/** create routes when the auth route mode is static */
export function createStaticRoutes() {
  const constantRoutes: ElegantRoute[] = [
    {
      name: 'root',
      path: '/',
      redirect: '/dashboard',
      meta: {
        title: 'root',
        constant: true,
        hideInMenu: true
      }
    } as unknown as ElegantRoute
  ];

  const blankNames = new Set(['403', '404', '500', 'login']);

  generatedRoutes.forEach(item => {
    if (item.meta?.constant || builtinNames.has(String(item.name))) {
      const name = String(item.name);
      const component =
        typeof item.component === 'string' && blankNames.has(name)
          ? item.component.replace('layout.base', 'layout.blank')
          : item.component;
      constantRoutes.push({
        ...item,
        component,
        meta: {
          ...item.meta,
          title: item.meta?.title || name,
          constant: true,
          hideInMenu: true
        }
      });
    }
  });

  return {
    constantRoutes,
    authRoutes: businessRoutes as unknown as ElegantRoute[]
  };
}

/**
 * Get auth vue routes
 *
 * @param routes Elegant routes
 */
export function getAuthVueRoutes(routes: ElegantConstRoute[]) {
  return transformElegantRoutesToVueRoutes(routes, layouts, views);
}

export type { CustomRoute };
