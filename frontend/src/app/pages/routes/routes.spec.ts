import { Route } from '../../core/models/models';
import { RoutesActions } from '../../store/routes/routes.actions';
import { routesFeature } from '../../store/routes/routes.reducer';
import { renderPage, stateAfter, text } from '../../testing/page-testing';
import { Routes } from './routes';

const route = (id: number, name: string, longName: string | null): Route =>
  ({
    id,
    gtfsId: `${id}`,
    name,
    shortName: name,
    longName,
    description: null,
    routeType: 3,
  }) as Route;

describe('Routes page', () => {
  it('lists the routes and filters them by name as you type', () => {
    const routes = [route(1, '103', 'Choroszcz'), route(2, '5', 'Dojlidy'), route(3, '10', null)];
    const { el, render } = renderPage(
      Routes,
      stateAfter(routesFeature, RoutesActions.loadSuccess({ routes })),
    );
    expect(el.querySelectorAll('tbody tr')).toHaveLength(3);

    const search = el.querySelector('input[type=search]') as HTMLInputElement;
    search.value = 'choros';
    search.dispatchEvent(new Event('input'));
    render();
    expect(el.querySelectorAll('tbody tr')).toHaveLength(1);
    expect(text(el.querySelector('tbody tr'))).toContain('103');

    search.value = 'nowhere';
    search.dispatchEvent(new Event('input'));
    render();
    expect(text(el.querySelector('tbody'))).toBe('No routes match.');
  });
});
