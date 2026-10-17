<?php
// Run inside Coolify against the installed model; all changes are rolled back.
define('PROVISION_SITE_FUNCTIONS_ONLY', true);
require $argv[1];
Illuminate\Support\Facades\DB::beginTransaction();
try {
    foreach (['bsok8o8csgso8c00g00k0csg', 'o84wgo08wcs048ss8sokgkgw'] as $uuid) {
        $a = App\Models\Application::where('uuid', $uuid)->firstOrFail();
        foreach ([false, true] as $preview) {
            setEnv($a, 'BUILDER_IMAGE', 'ineffective-secret', $preview, true);
            setEnv($a, 'AWS_ACCESS_KEY_ID', 'fixture-key', $preview, true);
        }
        configureDockerBuild($a);
        configureDockerBuild($a);
        $settings = $a->settings->fresh();
        requireThat($settings->use_build_secrets === true, 'Explicit CMS secret transport was disabled');
        requireThat($settings->inject_build_args_to_dockerfile === false, 'ARG injection remains enabled');
        requireThat($settings->include_source_commit_in_build === false, 'Commit metadata still invalidates build layers');
        foreach ([false, true] as $preview) {
            $relation = $preview ? $a->environment_variables_preview() : $a->environment_variables();
            requireThat(!$relation->where('key', 'BUILDER_IMAGE')->exists(), 'Ineffective builder secret remains');
            $keys = ($preview ? $a->environment_variables_preview() : $a->environment_variables())
                ->where('key', 'AWS_ACCESS_KEY_ID')->firstOrFail();
            requireThat(!$keys->is_buildtime && $keys->is_runtime, 'Runtime key leaked into build scope');
        }
        echo $uuid.": idempotent production/preview build policy passed\n";
    }
} finally {
    Illuminate\Support\Facades\DB::rollBack();
    echo "All writes rolled back.\n";
}
