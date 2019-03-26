package de.metas.paypalplus.model;

import lombok.Getter;
import lombok.NonNull;
import lombok.Value;

@Value
@Getter
public class PaymentStatus
{
	@NonNull
	String paymentId;

	@NonNull
	String authorizationId;

	@NonNull
	String paymentState;
}
