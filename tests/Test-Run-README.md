Running Tests

# Run everything
./run-tests.sh

# Run specific suites
./run-tests.sh 04 05        # Cases + Verification only

# Skip cleanup (keep test data for inspection)
./run-tests.sh --no-cleanup

# List available suites
./run-tests.sh --list