import subprocess, sys, os
import matplotlib.pyplot as plt
import yaml
def run_wrk_command(cmd):
    result = subprocess.run(cmd.split(), stdout=subprocess.PIPE)
    return result.stdout

def aggregate_command_results(cmd_results):
    lines = cmd_results.decode("utf-8").splitlines()
    results = []
    for i in range(0, len(lines)):
        words = lines[i].split()
        if (len(words) < 2)\
              or (not words[0].replace('.', '', 1).isdigit())\
              or (not words[1].replace('.', '', 1).isdigit()):
            continue
        results.append([float(words[0]), float(words[1]) * 100])
    return results

def build_command(common, profile):
    def take_param(param_name):
        return str(profile[param_name] if param_name in profile else common[param_name])
    command_args = ["wrk", "-t", take_param("threads"), \
                    "-c", take_param("connections"),\
                          "-d", take_param("duration"), \
                            "-s", take_param("lua_scripts_dir") + "/" + take_param("lua"),\
                                  "-R", take_param("rate"), "-L", take_param("url")]
    
    return ' '.join(command_args)

def start_test_case(common, test_case):
    fig, ax = plt.subplots(figsize=(5, 2.7))
    print(f'test case {test_case["name"]}')
    test_case_name = test_case["name"]
    save_path = common["output_dir"] + "/" + test_case_name
    if not os.path.exists(save_path):
        os.makedirs(save_path)
    for profile in test_case['profiles']:
        profile_name = profile['name']
        print(" . profile " + profile_name)
        command = build_command(common, profile)
        results = run_wrk_command(command)
        with open(save_path + "/" + profile_name, mode='wb') as file:
            file.write(results)
        aggregated = aggregate_command_results(results)
        ax.plot([t[1] for t in aggregated], [t[0] for t in aggregated], label=profile_name)
    ax.legend()
    fig.savefig(save_path + "/" + test_case_name + ".png")
def main():
    args = sys.argv
    if len(args) < 1:
        print("expected name of file with wrk commands")
        return
    with open(args[1]) as file:
        config = yaml.safe_load(file)
    
    common = config['common']
    for test_case in config['test_cases']:
        start_test_case(common, test_case)

if __name__ == '__main__':
    main()