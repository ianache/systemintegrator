import { Routes } from '@angular/router';
import { ConnectorsPageComponent } from './connectors/connectors-page.component';
import { ConsoleFrameComponent } from './console-frame.component';
import { CredentialsPageComponent } from './credentials/credentials-page.component';
import { DashboardPageComponent } from './dashboard/dashboard-page.component';
import { FlowDesignerComponent } from './flow/flow-designer.component';
import { FlowExecutionDetailComponent } from './flow/flow-execution-detail.component';
import { FlowExecutionsComponent } from './flow/flow-executions.component';
import { FlowListComponent } from './flow/flow-list.component';
import { IntegrationProfileDetailComponent } from './integration-profile/integration-profile-detail.component';
import { IntegrationProfileListComponent } from './integration-profile/integration-profile-list.component';
import { MonitorPageComponent } from './monitor/monitor-page.component';

export const CONSOLE_ROUTES: Routes = [
  {
    path: '',
    component: ConsoleFrameComponent,
    children: [
      { path: '', component: DashboardPageComponent },
      { path: 'profiles', component: IntegrationProfileListComponent },
      { path: 'profiles/:profileId', component: IntegrationProfileDetailComponent },
      { path: 'flows', component: FlowListComponent },
      { path: 'flows/:flowId', component: FlowDesignerComponent },
      { path: 'flows/:flowId/executions', component: FlowExecutionsComponent },
      { path: 'flows/:flowId/executions/:executionId', component: FlowExecutionDetailComponent },
      { path: 'monitor', component: MonitorPageComponent },
      { path: 'connectors', component: ConnectorsPageComponent },
      { path: 'credentials', component: CredentialsPageComponent },
    ],
  },
];
