{{/*
Base chart name.
*/}}
{{- define "systemintegrator.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Common labels shared by every resource in this chart.
*/}}
{{- define "systemintegrator.labels" -}}
helm.sh/chart: {{ printf "%s-%s" (include "systemintegrator.name" .) .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/part-of: {{ include "systemintegrator.name" . }}
{{- end -}}

{{/*
Selector labels for a given component, e.g. (include "systemintegrator.selectorLabels" (dict "root" $ "component" "app")).
*/}}
{{- define "systemintegrator.selectorLabels" -}}
app.kubernetes.io/name: {{ include "systemintegrator.name" .root }}-{{ .component }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end -}}

{{/*
Full labels (common + selector) for a given component.
*/}}
{{- define "systemintegrator.componentLabels" -}}
{{ include "systemintegrator.labels" .root }}
{{ include "systemintegrator.selectorLabels" . }}
{{- end -}}

{{/*
Release-scoped resource name for a given component, e.g.
(include "systemintegrator.fullname" (dict "root" $ "component" "app")).
*/}}
{{- define "systemintegrator.fullname" -}}
{{- printf "%s-%s" .root.Release.Name .component | trunc 63 | trimSuffix "-" -}}
{{- end -}}
