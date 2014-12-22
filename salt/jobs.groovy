// Salt Jenkins jobs seed script
import groovy.text.*
import lib.Admins

// Common variable Definitions
def github_repo = 'saltstack/salt'
def repo_api = new URL("https://api.github.com/repos/${github_repo}")
def repo_data = new groovy.json.JsonSlurper().parse(repo_api.newReader())
def project_description = repo_data['description']

// Job rotation defaults
def default_days_to_keep = 90
def default_nr_of_jobs_to_keep = 180
def default_artifact_days_to_keep = default_days_to_keep
def default_artifact_nr_of_jobs_to_keep = default_nr_of_jobs_to_keep

// Job Timeout defauls
def default_timeout_percent = 150
def default_timeout_builds = 10
def default_timeout_minutes = 60

// Get branches to build from GitHub. Only branches matching 'develop' and 'dddd.dd'
def branches_api = new URL("https://api.github.com/repos/${github_repo}/branches")
def salt_branches_data = new groovy.json.JsonSlurper().parse(branches_api.newReader())
def salt_branches = []
salt_branches_data.each {
  salt_branches.add(it.name)
}
salt_branches = salt_branches.grep(~/(develop|([\d]{4}.[\d]{1,2}))/)

def salt_build_types = [
    'Cloud': [
        'Arch',
        'CentOS 5',
        'CentOS 6',
        'CentOS 7',
        'Debian 7',
        'Fedora 20',
        'openSUSE 13',
        'Ubuntu 10.04',
        'Ubuntu 12.04',
        'Ubuntu 14.04'
    ],
    'KVM': [
    ]
]

def salt_cloud_providers = [
    'Linode',
    'Rackspace'
]


FLOW_SCRIPT_TEMPLATE_TEXT = '''\
import hudson.FilePath

guard {
    retry(3) {
        clone = build("salt/${branch_name}/clone")
    }

    // Let's run Lint & Unit in parallel
    parallel (
        {
            lint = build(
                "salt/${branch_name}/lint",
                CLONE_BUILD_ID: clone.build.number
            )
        },
        <% vm_names.each { name, job_name -> %>
        {
            ${name} = build(
                "salt/${branch_name}/${build_type}/<%
                    if ( build_type.toLowerCase() == 'cloud') { %>params["PROVIDER"].toLowerCase()/<% }
                %>${job_name}",
                GIT_COMMIT: params["GIT_COMMIT"]
            )
        },<% } %>
    )
} rescue {
    // Let's instantiate the build flow toolbox
    def toolbox = extension.'build-flow-toolbox'

    local_lint_workspace_copy = build.workspace.child('lint')
    local_lint_workspace_copy.mkdirs()
    toolbox.copyFiles(lint.workspace, local_lint_workspace_copy)

    <% vm_names.each { name, job_name -> %>
    local_${name}_workspace_copy = build.workspace.child('${job_name}')
    local_${name}_workspace_copy.mkdirs()
    toolbox.copyFiles(${name}.workspace, local_${name}_workspace_copy)
    <% } %>
   /*
    *  Copy the clone build changelog.xml into this jobs root for proper changelog report
    *  This does not currently work but is here for future reference
    */
    def clone_changelog = new FilePath(clone.getRootDir()).child('changelog.xml')
    def build_changelog = new FilePath(build.getRootDir()).child('changelog.xml')
    build_changelog.copyFrom(clone_changelog)

    // Delete the child workspaces directory
    lint.workspace.deleteRecursive()

    <% vm_names.each { name, job_name -> %>
    ${name}.workspace.deleteRecursive()<% } %>
}
'''

// Define the folder structure
folder {
    name('salt')
    displayName('Salt')
    description = project_description
}

salt_branches.each { branch_name ->
    def branch_folder_name = "salt/${branch_name.toLowerCase()}"
    folder {
        name(branch_folder_name)
        displayName("${branch_name.capitalize()} Branch")
        description = project_description
    }

    salt_build_types.each { build_type, vm_names ->
        if ( vm_names != [] ) {
            def build_type_folder_name = "${branch_folder_name}/${build_type.toLowerCase()}"
            folder {
                name(build_type_folder_name)
                displayName("${build_type} Builds")
                description = project_description
            }

            if (build_type.toLowerCase() == 'cloud') {
                salt_cloud_providers.each { provider_name ->
                    cloud_provider_folder_name = "${build_type_folder_name}/${provider_name.toLowerCase()}"
                    folder {
                        name(cloud_provider_folder_name)
                        displayName(provider_name)
                        description = project_description
                    }
                }
            }
        }
    }
}


salt_branches.each { branch_name ->
    // Clone Job
    job {
        name = "salt/${branch_name}/clone"
        displayName('Clone Repository')

        concurrentBuild(allowConcurrentBuild = true)
        description(project_description + ' - Clone Repository')
        label('worker')

        configure {
            job_properties = it.get('properties').get(0)
            job_properties.appendNode(
                'hudson.plugins.copyartifact.CopyArtifactPermissionProperty').appendNode(
                    'projectNameList').appendNode(
                        'string').setValue("salt/${branch_name}/*")
            github_project_property = job_properties.appendNode(
                'com.coravy.hudson.plugins.github.GithubProjectProperty')
            github_project_property.appendNode('projectUrl').setValue("https://github.com/${github_repo}")
        }

        wrappers {
            // Inject global defined passwords in the environment
            injectPasswords()

            // Cleanup the workspace before starting
            preBuildCleanup()

            // Add timestamps to console log
            timestamps()

            // Color Support to console log
            colorizeOutput('xterm')

            // Build Timeout
            timeout {
                elastic(
                    percentage = default_timeout_percent,
                    numberOfBuilds = default_timeout_builds,
                    minutesDefault= default_timeout_minutes
                )
                writeDescription('Build failed due to timeout after {0} minutes')
            }
        }

        // Delete old jobs
        /* Since we're just cloning the repository in order to make it an artifact to
        * user as workspace for all other jobs, we only need to keep the artifact for
        * a couple of minutes. Since one day is the minimum....
        */
        logRotator(
            default_days_to_keep,
            default_nr_of_jobs_to_keep,
            1,  //default_artifact_days_to_keep,
            default_artifact_nr_of_jobs_to_keep
        )

        // scm configuration
        scm {
            github(
                github_repo,
                branch = "*/${branch_name}",
                protocol = 'https'
            )
        }
        checkoutRetryCount(3)

        environmentVariables {
            env('GITHUB_REPO', github_repo)
            env('COMMIT_STATUS_CONTEXT', 'ci/clone')
            env('VIRTUALENV_NAME', "salt-${branch_name}")
            env('VIRTUALENV_SETUP_STATE_NAME', 'projects.clone')
        }

        // Job Steps
        steps {
            // Setup the required virtualenv
            shell(readFileFromWorkspace('jenkins-seed', 'scripts/prepare-virtualenv.sh'))

            // Set initial commit status
            shell(readFileFromWorkspace('jenkins-seed', 'scripts/set-commit-status.sh'))

            // Compress the checked out workspace
            shell(readFileFromWorkspace('jenkins-seed', 'scripts/compress-workspace.sh'))
        }

        publishers {
            archiveArtifacts('workspace.cpio.xz')

            postBuildTask {
                // Set final commit status
                task('.', readFileFromWorkspace('jenkins-seed', 'scripts/set-commit-status.sh'))
            }
        }
    }

    // Lint Job
    job {
        name = "salt/${branch_name}/lint"
        displayName('Lint')
        concurrentBuild(allowConcurrentBuild = true)
        description(project_description + ' - Code Lint')
        label('worker')

        // Parameters Definition
        parameters {
            stringParam('CLONE_BUILD_ID')
        }

        configure {
            job_properties = it.get('properties').get(0)
            github_project_property = job_properties.appendNode(
                'com.coravy.hudson.plugins.github.GithubProjectProperty')
            github_project_property.appendNode('projectUrl').setValue("https://github.com/${github_repo}")
        }

        wrappers {
            // Inject global defined passwords in the environment
            injectPasswords()

            // Cleanup the workspace before starting
            preBuildCleanup()

            // Add timestamps to console log
            timestamps()

            // Color Support to console log
            colorizeOutput('xterm')

            // Build Timeout
            timeout {
                elastic(
                    percentage = default_timeout_percent,
                    numberOfBuilds = default_timeout_builds,
                    minutesDefault= default_timeout_minutes
                )
                writeDescription('Build failed due to timeout after {0} minutes')
            }
        }

        // Delete old jobs
        logRotator(
            default_days_to_keep,
            default_nr_of_jobs_to_keep,
            default_artifact_days_to_keep,
            default_artifact_nr_of_jobs_to_keep
        )

        environmentVariables {
            env('GITHUB_REPO', github_repo)
            env('COMMIT_STATUS_CONTEXT', 'ci/lint')
            env('VIRTUALENV_NAME', "salt-${branch_name}")
            env('VIRTUALENV_SETUP_STATE_NAME', 'projects.salt.lint')
        }

        // Job Steps
        steps {
            // Setup the required virtualenv
            shell(readFileFromWorkspace('jenkins-seed', 'scripts/prepare-virtualenv.sh'))

            // Set initial commit status
            shell(readFileFromWorkspace('jenkins-seed', 'scripts/set-commit-status.sh'))

            // Copy the workspace artifact
            copyArtifacts("salt/${branch_name}/clone", 'workspace.cpio.xz') {
                buildNumber('${CLONE_BUILD_ID}')
            }
            shell(readFileFromWorkspace('jenkins-seed', 'scripts/decompress-workspace.sh'))

            // Run Lint Code
            shell(readFileFromWorkspace('jenkins-seed', 'libnacl/scripts/run-lint.sh'))
        }

        publishers {
            // Report Violations
            violations {
                pylint(10, 999, 999, 'pylint-report*.xml')
            }

            postBuildTask {
                // Set final commit status
                task('.', readFileFromWorkspace('jenkins-seed', 'scripts/set-commit-status.sh'))
            }
       }
    }

    salt_build_types.each { build_type, vm_names ->

        if ( vm_names != [] ) {
            job(type: BuildFlow) {
                name = "salt/${build_type.toLowerCase()}-main-build"
                displayName("${branch_name.capitalize()} Branch ${build_type} Main Build")
                description(project_description)
                label('worker')
                concurrentBuild(allowConcurrentBuild = true)

                parameters {
                    choiceParam('PROVIDER', salt_cloud_providers)
                }

                configure {
                    it.appendNode('buildNeedsWorkspace').setValue(true)
                    job_publishers = it.get('publishers').get(0)
                    job_publishers.appendNode(
                        'org.zeroturnaround.jenkins.flowbuildtestaggregator.FlowTestAggregator',
                        [plugin: 'build-flow-test-aggregator']
                    )
                    job_properties = it.get('properties').get(0)
                    github_project_property = job_properties.appendNode(
                        'com.coravy.hudson.plugins.github.GithubProjectProperty')
                    github_project_property.appendNode('projectUrl').setValue("https://github.com/${github_repo}")
                    slack_notifications = job_properties.appendNode(
                        'jenkins.plugins.slack.SlackNotifier_-SlackJobProperty')
                    slack_notifications.appendNode('room').setValue('#jenkins')
                    slack_notifications.appendNode('startNotifications').setValue(false)
                    slack_notifications.appendNode('notifySuccess').setValue(true)
                    slack_notifications.appendNode('notifyAborted').setValue(true)
                    slack_notifications.appendNode('notifyNotBuilt').setValue(true)
                    slack_notifications.appendNode('notifyFailure').setValue(true)
                    slack_notifications.appendNode('notifyBackToNormal').setValue(true)
                    job_publishers.appendNode(
                        'jenkins.plugins.slack.SlackNotifier',
                        [plugin: slack]
                    )
                }

                wrappers {
                    // Inject global defined passwords in the environment
                    injectPasswords()

                    // Add timestamps to console log
                    timestamps()

                    // Color Support to console log
                    colorizeOutput('xterm')

                    // Build Timeout
                    timeout {
                        elastic(
                            percentage = default_timeout_percent,
                            numberOfBuilds = default_timeout_builds,
                            minutesDefault= default_timeout_minutes
                        )
                        writeDescription('Build failed due to timeout after {0} minutes')
                    }
                }

                // Delete old jobs
                logRotator(
                    default_days_to_keep,
                    default_nr_of_jobs_to_keep,
                    default_artifact_days_to_keep,
                    default_artifact_nr_of_jobs_to_keep
                )

                // Job Triggers
                /* triggers disabled for now
                triggers {
                    githubPush()
                }
                */

                template_vm_data = []
                vm_names.each { vm_name ->
                    def vm_name_nospc = vm_name.toLowerCase().replace(' ', '-')
                    def vm_name_nodots = vm_name.toLowerCase().replace(' ', '_').replace('.', '_')
                    template_vm_data.add(
                        [vm_name_nodots, vm_name_nospc]
                    )
                }
                template_context = [
                    build_type: build_type.toLowerCase(),
                    branch_name: branch_name,
                    vm_names: template_vm_data
                ]
                template_engine = new SimpleTemplateEngine()
                flow_script_template = template_engine.createTemplate(FLOW_SCRIPT_TEMPLATE_TEXT)
                flow_script_template_text = flow_script_template.make(template_context)

                buildFlow(flow_script_template_text.toString())

                publishers {
                    // Report Coverage
                    //cobertura('unit/coverage.xml') {
                    //    failNoReports = false
                    //}
                    // Report Violations
                    violations {
                        pylint(10, 999, 999, 'lint/pylint-report*.xml')
                    }

                    // Cleanup workspace
                    wsCleanup()
                }
            }

            if (build_type.toLowerCase() == 'cloud') {
                salt_cloud_providers.each { provider_name ->
                    vm_names.each { vm_name ->
                        def job_name = vm_name.toLowerCase().replace(' ', '-')
                        def vm_name_nodots = vm_name.replace(' ', '_').replace('.', '_')
                        job {
                            name = "salt/${branch_name}/${build_type.toLowerCase()}/${provider_name.toLowerCase()}/${job_name}"
                            displayName(vm_name)
                            concurrentBuild(allowConcurrentBuild = true)
                            description("${project_description} - ${build_type} - ${provider_name} - ${vm_name}")
                            label('cloud')

                            // Parameters Definition
                            parameters {
                                stringParam('GIT_COMMIT')
                            }

                            configure {
                                job_properties = it.get('properties').get(0)
                                github_project_property = job_properties.appendNode(
                                    'com.coravy.hudson.plugins.github.GithubProjectProperty')
                                    github_project_property.appendNode('projectUrl').setValue("https://github.com/${github_repo}")
                            }

                            wrappers {
                                // Inject global defined passwords in the environment
                                injectPasswords()

                                // Cleanup the workspace before starting
                                preBuildCleanup()

                                // Add timestamps to console log
                                timestamps()

                                // Color Support to console log
                                colorizeOutput('xterm')

                                // Build Timeout
                                timeout {
                                    elastic(
                                        percentage = default_timeout_percent,
                                        numberOfBuilds = default_timeout_builds,
                                        minutesDefault= default_timeout_minutes
                                    )
                                    writeDescription('Build failed due to timeout after {0} minutes')
                                }
                            }

                            environmentVariables {
                                env('GITHUB_REPO', github_repo)
                                env('COMMIT_STATUS_CONTEXT', "ci/${job_name}")
                                env('VIRTUALENV_NAME', "salt-${branch_name}")
                                env('VIRTUALENV_SETUP_STATE_NAME', 'projects.salt.unit')
                                env('BUILD_VM_NAME', "${provider_name.toLowerCase()}_${vm_name.replace(' ', '_').replace('.', '_')}")
                            }

                            // Job Steps
                            steps {
                                // Setup the required virtualenv
                                shell(readFileFromWorkspace('jenkins-seed', 'scripts/prepare-virtualenv.sh'))

                                // Set initial commit status
                                shell(readFileFromWorkspace('jenkins-seed', 'scripts/set-commit-status.sh'))

                                // Generate the required environment variables
                                environmentVariables {
                                    script(
                                        readFileFromWorkspace(
                                            'jenkins-seed',
                                            'salt/scripts/branches-environment-variables.sh'
                                        )
                                    )
                                }

                                // Run Unit Tests
                                shell(readFileFromWorkspace('jenkins-seed', 'salt/scripts/branches-run-tests.sh'))
                            }

                            publishers {
                                // Archive Artifacts
                                archiveArtifacts('artifacts/logs/*,artifacts/packages/*')
                                // Report Coverage
                                cobertura('artifacts/coverage/coverage.xml') {
                                    failNoReports = false
                                }

                                // Junit Reports
                                archiveJunit('artifacts/unittests/*.xml') {
                                    retainLongStdout(true)
                                    testDataPublishers {
                                        publishTestStabilityData()
                                    }
                                }

                                postBuildTask {
                                    // Download remote files
                                    task('.', readFileFromWorkspace('jenkins-seed', 'salt/scripts/download-remote-files.sh'))
                                    // Shutdown VM
                                    task('.', readFileFromWorkspace('jenkins-seed', 'salt/scripts/shutdown-cloud-vm.sh'))
                                    // Set final commit status
                                    task('.', readFileFromWorkspace('jenkins-seed', 'scripts/set-commit-status.sh'))
                                }
                            }
                        }
                    }
                }
            } else {
                vm_names.each { vm_name ->
                    def job_name = vm_name.toLowerCase().replace(' ', '-')
                    job {
                        name = "salt/${branch_name}/${build_type.toLowerCase()}/${job_name}"
                        displayName(vm_name)
                        concurrentBuild(allowConcurrentBuild = true)
                        description("${project_description} - ${build_type} - ${vm_name}")
                        label('container')

                        // Parameters Definition
                        parameters {
                            stringParam('CLONE_BUILD_ID')
                        }

                        configure {
                            job_properties = it.get('properties').get(0)
                            github_project_property = job_properties.appendNode(
                                'com.coravy.hudson.plugins.github.GithubProjectProperty')
                                github_project_property.appendNode('projectUrl').setValue("https://github.com/${github_repo}")
                        }

                        wrappers {
                            // Inject global defined passwords in the environment
                            injectPasswords()

                            // Cleanup the workspace before starting
                            preBuildCleanup()

                            // Add timestamps to console log
                            timestamps()

                            // Color Support to console log
                            colorizeOutput('xterm')

                            // Build Timeout
                            timeout {
                                elastic(
                                    percentage = default_timeout_percent,
                                    numberOfBuilds = default_timeout_builds,
                                    minutesDefault= default_timeout_minutes
                                )
                                writeDescription('Build failed due to timeout after {0} minutes')
                            }
                        }

                        environmentVariables {
                            env('GITHUB_REPO', github_repo)
                            env('COMMIT_STATUS_CONTEXT', "ci/${job_name}")
                            env('VIRTUALENV_NAME', "salt-${branch_name}")
                            env('VIRTUALENV_SETUP_STATE_NAME', 'projects.salt.unit')
                            env('BUILD_VM_NAME', vm_name.replace(' ', '_').replace('.', '_'))
                        }

                        // Job Steps
                        steps {
                            // Setup the required virtualenv
                            shell(readFileFromWorkspace('jenkins-seed', 'scripts/prepare-virtualenv.sh'))

                            // Set initial commit status
                            shell(readFileFromWorkspace('jenkins-seed', 'scripts/set-commit-status.sh'))

                            // Generate the required environment variables
                            environmentVariables {
                                script(
                                    readFileFromWorkspace(
                                        'jenkins-seed',
                                        'salt/scripts/branches-environment-variables.sh'
                                    )
                                )
                            }

                            // Run Unit Tests
                            shell(readFileFromWorkspace('jenkins-seed', 'salt/scripts/branches-run-tests.sh'))
                        }

                        publishers {
                            // Archive Artifacts
                            archiveArtifacts('artifacts/logs/*,artifacts/packages/*')

                            // Report Coverage
                            cobertura('artifacts/coverage/coverage.xml') {
                                failNoReports = false
                            }

                            // Junit Reports
                            archiveJunit('artifacts/unittests/*.xml') {
                                retainLongStdout(true)
                                testDataPublishers {
                                    publishTestStabilityData()
                                }
                            }

                            postBuildTask {
                                // Download remote files
                                task('.', readFileFromWorkspace('jenkins-seed', 'salt/scripts/download-remote-files.sh'))
                                // Shutdown VM
                                task('.', readFileFromWorkspace('jenkins-seed', 'salt/scripts/shutdown-cloud-vm.sh'))
                                // Set final commit status
                                task('.', readFileFromWorkspace('jenkins-seed', 'scripts/set-commit-status.sh'))
                            }
                        }
                    }
                }
            }
        }
    }
}
