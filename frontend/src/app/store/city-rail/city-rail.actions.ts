import { createActionGroup, emptyProps, props } from '@ngrx/store';
import {
  CityRailProposal,
  CityRailReport,
  ProposalVariant,
  RailBusComparison,
} from '../../core/models/models';

export const CityRailActions = createActionGroup({
  source: 'City rail',
  events: {
    Load: emptyProps(),
    Loaded: props<{ report: CityRailReport }>(),
    Failure: props<{ error: string }>(),
    'Load comparison': emptyProps(),
    'Comparison loaded': props<{ comparison: RailBusComparison }>(),
    'Comparison failure': props<{ error: string }>(),
    'Load proposal': emptyProps(),
    'Proposal loaded': props<{ proposal: CityRailProposal }>(),
    'Proposal failure': props<{ error: string }>(),
    'Load variant': props<{ solution: string; frequency: string }>(),
    'Variant loaded': props<{ solution: string; variant: ProposalVariant }>(),
    'Variant failure': props<{ error: string }>(),
  },
});
