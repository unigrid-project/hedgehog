package org.unigrid.hedgehog;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

public class LicenseInformationSteps {
	@Given("The application is started with the version option supplied")
	public void the_application_is_started_with_the_version_option_supplied() {
		//final ByteArrayOutputStream stream = new ByteArrayOutputStream();
		//System.setOut(new PrintStream(stream));
		//Hedgehog.main(new String[]{"--version"});
	}

	@When("The output is returned")
	public void the_output_is_returned() {
		// Write code here that turns the phrase above into concrete actions
		//throw new io.cucumber.java.PendingException();
	}

	@Then("It should contain version and license information")
	public void it_should_contain_version_and_license_information() {
		// Write code here that turns the phrase above into concrete actions
		//throw new io.cucumber.java.PendingException();
	}
}
