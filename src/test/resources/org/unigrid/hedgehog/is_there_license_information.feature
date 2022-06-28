Feature: Show license information
	Is a valid license defined ?

	Scenario: There is a AGPL license note
		Given The application is started with the version option supplied
		When The output is returned
		Then It should contain version and license information
